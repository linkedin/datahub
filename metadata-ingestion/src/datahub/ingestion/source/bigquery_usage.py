import collections
import dataclasses
import enum
import logging
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Counter, Dict, Iterable, List, Optional, Union

import cachetools
import pydantic
from google.cloud.logging_v2.client import Client as GCPLoggingClient

import datahub.emitter.mce_builder as builder
from datahub.configuration.common import ConfigModel
from datahub.ingestion.api.common import PipelineContext
from datahub.ingestion.api.source import Source, SourceReport
from datahub.ingestion.api.workunit import UsageStatsWorkUnit
from datahub.metadata.schema_classes import (
    UsageAggregationClass,
    UsageAggregationMetricsClass,
    UsersUsageCountsClass,
    WindowDurationClass,
)
from datahub.utilities.delayed_iter import delayed_iter

logger = logging.getLogger(__name__)

# ProtobufEntry is generated dynamically using a namedtuple, so mypy
# can't really deal with it. As such, we short circuit mypy's typing
# but keep the code relatively clear by retaining dummy types.
#
# from google.cloud.logging_v2 import ProtobufEntry
# AuditLogEntry = ProtobufEntry
AuditLogEntry = Any

DEBUG_INCLUDE_FULL_PAYLOADS = False
GCP_LOGGING_PAGE_SIZE = 1000

# Handle yearly, monthly, daily, or hourly partitioning.
# See https://cloud.google.com/bigquery/docs/partitioned-tables.
PARTITIONED_TABLE_REGEX = re.compile(r"^(.+)_(\d{4}|\d{6}|\d{8}|\d{10})$")

BQ_DATETIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
BQ_FILTER_RULE_TEMPLATE = """
protoPayload.serviceName="bigquery.googleapis.com"
AND
(
    (
        protoPayload.methodName="jobservice.jobcompleted"
        AND
        protoPayload.serviceData.jobCompletedEvent.eventName="query_job_completed"
        AND
        protoPayload.serviceData.jobCompletedEvent.job.jobStatus.state="DONE"
        AND
        NOT protoPayload.serviceData.jobCompletedEvent.job.jobStatus.error.code:*
    )
    OR
    (
        protoPayload.metadata.tableDataRead:*
    )
)
AND
timestamp >= "{start_time}"
AND
timestamp <= "{end_time}"
""".strip()


@enum.unique
class _BucketDuration(str, enum.Enum):
    DAY = WindowDurationClass.DAY
    HOUR = WindowDurationClass.HOUR


def get_time_bucket(original: datetime, bucketing: _BucketDuration) -> datetime:
    """Floors the timestamp to the closest day or hour."""

    if bucketing == _BucketDuration.HOUR:
        return original.replace(minute=0, second=0, microsecond=0)
    else:  # day
        return original.replace(hour=0, minute=0, second=0, microsecond=0)


def get_bucket_duration_delta(bucketing: _BucketDuration) -> timedelta:
    if bucketing == _BucketDuration.HOUR:
        return timedelta(hours=1)
    else:  # day
        return timedelta(days=1)


@dataclass(frozen=True, order=True)
class BigQueryTableRef:
    project: str
    dataset: str
    table: str

    @classmethod
    def from_spec_obj(cls, spec: dict) -> "BigQueryTableRef":
        return BigQueryTableRef(spec["projectId"], spec["datasetId"], spec["tableId"])

    @classmethod
    def from_string_name(cls, ref: str) -> "BigQueryTableRef":
        parts = ref.split("/")
        if parts[0] != "projects" or parts[2] != "datasets" or parts[4] != "tables":
            raise ValueError(f"invalid BigQuery table reference: {ref}")
        return BigQueryTableRef(parts[1], parts[3], parts[5])

    def is_anonymous(self) -> bool:
        # Temporary tables will have a dataset that begins with an underscore.
        return self.dataset.startswith("_")

    def remove_extras(self) -> "BigQueryTableRef":
        if "$" in self.table or "@" in self.table:
            raise ValueError(f"cannot handle {self} - poorly formatted table name")

        # Handle partitioned and sharded tables.
        matches = PARTITIONED_TABLE_REGEX.match(self.table)
        if matches:
            return BigQueryTableRef(self.project, self.dataset, matches.group(1))

        return self

    def __str__(self) -> str:
        return f"projects/{self.project}/datasets/{self.dataset}/tables/{self.table}"


def _table_ref_to_urn(ref: BigQueryTableRef, env: str) -> str:
    return builder.make_dataset_urn(
        "bigquery", f"{ref.project}.{ref.dataset}.{ref.table}", env
    )


def _job_name_ref(project: str, jobId: str) -> str:
    return f"projects/{project}/jobs/{jobId}"


@dataclass
class ReadEvent:
    timestamp: datetime
    actor_email: str

    resource: BigQueryTableRef
    fieldsRead: List[str]
    readReason: str
    jobName: Optional[str]

    payload: Any

    # We really should use composition here since the query isn't actually
    # part of the read event, but this solution is just simpler.
    query: Optional[str] = None  # populated via join

    @classmethod
    def can_parse_entry(cls, entry: AuditLogEntry) -> bool:
        try:
            entry.payload["metadata"]["tableDataRead"]
            return True
        except (KeyError, TypeError):
            return False

    @classmethod
    def from_entry(cls, entry: AuditLogEntry) -> "ReadEvent":
        user = entry.payload["authenticationInfo"]["principalEmail"]
        resourceName = entry.payload["resourceName"]
        readInfo = entry.payload["metadata"]["tableDataRead"]

        fields = readInfo["fields"]
        readReason = readInfo["reason"]
        jobName = None
        if readReason == "JOB":
            jobName = readInfo["jobName"]

        readEvent = ReadEvent(
            actor_email=user,
            timestamp=entry.timestamp,
            resource=BigQueryTableRef.from_string_name(resourceName),
            fieldsRead=fields,
            readReason=readReason,
            jobName=jobName,
            payload=entry.payload if DEBUG_INCLUDE_FULL_PAYLOADS else None,
        )
        return readEvent


@dataclass
class QueryEvent:
    timestamp: datetime
    actor_email: str

    query: str
    destinationTable: Optional[BigQueryTableRef]
    referencedTables: Optional[List[BigQueryTableRef]]
    jobName: str

    payload: Any

    @classmethod
    def can_parse_entry(cls, entry: AuditLogEntry) -> bool:
        try:
            entry.payload["serviceData"]["jobCompletedEvent"]["job"]
            return True
        except (KeyError, TypeError):
            return False

    @classmethod
    def from_entry(cls, entry: AuditLogEntry) -> "QueryEvent":
        user = entry.payload["authenticationInfo"]["principalEmail"]

        job = entry.payload["serviceData"]["jobCompletedEvent"]["job"]
        jobName = _job_name_ref(job["jobName"]["projectId"], job["jobName"]["jobId"])
        rawQuery = job["jobConfiguration"]["query"]["query"]

        rawDestTable = job["jobConfiguration"]["query"]["destinationTable"]
        destinationTable = None
        if rawDestTable:
            destinationTable = BigQueryTableRef.from_spec_obj(rawDestTable)

        rawRefTables = job["jobStatistics"].get("referencedTables")
        referencedTables = None
        if rawRefTables:
            referencedTables = [
                BigQueryTableRef.from_spec_obj(spec) for spec in rawRefTables
            ]
        # if job['jobConfiguration']['query']['statementType'] != "SCRIPT" and not referencedTables:
        #     breakpoint()

        queryEvent = QueryEvent(
            timestamp=entry.timestamp,
            actor_email=user,
            query=rawQuery,
            destinationTable=destinationTable,
            referencedTables=referencedTables,
            jobName=jobName,
            payload=entry.payload if DEBUG_INCLUDE_FULL_PAYLOADS else None,
        )
        return queryEvent


@dataclass
class AggregatedDataset:
    bucket_start_time: datetime
    resource: BigQueryTableRef

    queryFreq: Counter[str] = dataclasses.field(default_factory=collections.Counter)
    userCounts: Counter[str] = dataclasses.field(default_factory=collections.Counter)
    # TODO add column usage counters


class BigQueryUsageConfig(ConfigModel):
    project_id: Optional[str] = None
    extra_client_options: dict = {}
    env: str = builder.DEFAULT_ENV

    # start_time and end_time will be populated by the validators.
    bucket_duration: _BucketDuration = _BucketDuration.DAY
    end_time: datetime = None  # type: ignore
    start_time: datetime = None  # type: ignore

    query_log_delay: pydantic.PositiveInt = 100
    top_n_queries: Optional[pydantic.PositiveInt] = 10

    @pydantic.validator("end_time", pre=True, always=True)
    def default_end_time(cls, v, *, values, **kwargs):
        return v or get_time_bucket(
            datetime.now(tz=timezone.utc), values["bucket_duration"]
        )

    @pydantic.validator("start_time", pre=True, always=True)
    def default_start_time(cls, v, *, values, **kwargs):
        return v or (
            values["end_time"] - get_bucket_duration_delta(values["bucket_duration"])
        )


@dataclass
class BigQueryUsageSourceReport(SourceReport):
    dropped_table: Counter[str] = dataclasses.field(default_factory=collections.Counter)

    def report_dropped(self, key: str) -> None:
        self.dropped_table[key] += 1


class BigQueryUsageSource(Source):
    config: BigQueryUsageConfig
    report: BigQueryUsageSourceReport

    client: GCPLoggingClient

    def __init__(self, config: BigQueryUsageConfig, ctx: PipelineContext):
        super().__init__(ctx)
        self.config = config
        self.report = BigQueryUsageSourceReport()

        client_options = self.config.extra_client_options.copy()
        if self.config.project_id is not None:
            client_options["project"] = self.config.project_id

        # See https://github.com/googleapis/google-cloud-python/issues/2674 for
        # why we disable gRPC here.
        self.client = GCPLoggingClient(**client_options, _use_grpc=False)

    @classmethod
    def create(cls, config_dict: dict, ctx: PipelineContext) -> "BigQueryUsageSource":
        config = BigQueryUsageConfig.parse_obj(config_dict)
        return cls(config, ctx)

    def get_workunits(self) -> Iterable[UsageStatsWorkUnit]:
        bigquery_log_entries = self._get_bigquery_log_entries()
        parsed_events = self._parse_bigquery_log_entries(bigquery_log_entries)
        hydrated_read_events = self._join_events_by_job_id(parsed_events)
        aggregated_info = self._aggregate_enriched_read_events(hydrated_read_events)

        for time_bucket in aggregated_info.values():
            for aggregate in time_bucket.values():
                wu = self._make_usage_stat(aggregate)
                self.report.report_workunit(wu)
                yield wu

    def _get_bigquery_log_entries(self) -> Iterable[AuditLogEntry]:
        filter = BQ_FILTER_RULE_TEMPLATE.format(
            start_time=self.config.start_time.strftime(BQ_DATETIME_FORMAT),
            end_time=self.config.end_time.strftime(BQ_DATETIME_FORMAT),
        )

        entry: AuditLogEntry
        for i, entry in enumerate(
            self.client.list_entries(filter_=filter, page_size=GCP_LOGGING_PAGE_SIZE)
        ):
            if i % GCP_LOGGING_PAGE_SIZE == 0:
                logger.info("processing log entry %d", i)
            yield entry
        logger.debug("finished loading log entries from BigQuery")

    def _parse_bigquery_log_entries(
        self, entries: Iterable[AuditLogEntry]
    ) -> Iterable[Union[ReadEvent, QueryEvent]]:
        for entry in entries:
            event: Union[ReadEvent, QueryEvent]
            if ReadEvent.can_parse_entry(entry):
                event = ReadEvent.from_entry(entry)
            elif QueryEvent.can_parse_entry(entry):
                event = QueryEvent.from_entry(entry)
            else:
                self.report.report_failure(
                    f"{entry.log_name}-{entry.insert_id}",
                    f"unable to parse log entry: {entry!r}",
                )
            yield event

    def _join_events_by_job_id(
        self, events: Iterable[Union[ReadEvent, QueryEvent]]
    ) -> Iterable[ReadEvent]:
        # We only store the most recently used query events, which are used when
        # resolving job information within the read events.
        query_jobs: cachetools.LRUCache[str, QueryEvent] = cachetools.LRUCache(
            maxsize=2 * self.config.query_log_delay
        )

        def event_processor(
            events: Iterable[Union[ReadEvent, QueryEvent]]
        ) -> Iterable[ReadEvent]:
            for event in events:
                if isinstance(event, QueryEvent):
                    query_jobs[event.jobName] = event
                else:
                    yield event

        # TRICKY: To account for the possibility that the query event arrives after
        # the read event in the audit logs, we wait for at least `query_log_delay`
        # additional events to be processed before attempting to resolve BigQuery
        # job information from the logs.
        original_read_events = event_processor(events)
        delayed_read_events = delayed_iter(
            original_read_events, self.config.query_log_delay
        )

        for event in delayed_read_events:
            if event.jobName:
                if event.jobName in query_jobs:
                    # Join the query log event into the table read log event.
                    event.query = query_jobs[event.jobName].query

                    # TODO also join into the query itself for column references
                else:
                    self.report.report_warning(
                        "<general>",
                        "failed to match table read event with job; try increasing `query_log_delay`",
                    )

            yield event

    def _aggregate_enriched_read_events(
        self, events: Iterable[ReadEvent]
    ) -> Dict[datetime, Dict[BigQueryTableRef, AggregatedDataset]]:
        # TODO: handle partitioned tables

        # TODO: perhaps we need to continuously prune this, rather than
        # storing it all in one big object.
        datasets: Dict[
            datetime, Dict[BigQueryTableRef, AggregatedDataset]
        ] = collections.defaultdict(dict)

        for event in events:
            floored_ts = get_time_bucket(event.timestamp, self.config.bucket_duration)
            resource = event.resource.remove_extras()

            if resource.is_anonymous():
                self.report.report_dropped(str(resource))
                continue

            agg_bucket = datasets[floored_ts].setdefault(
                resource,
                AggregatedDataset(bucket_start_time=floored_ts, resource=resource),
            )

            agg_bucket.userCounts[event.actor_email] += 1
            if event.query:
                agg_bucket.queryFreq[event.query] += 1

        return datasets

    def _make_usage_stat(self, agg: AggregatedDataset) -> UsageStatsWorkUnit:
        return UsageStatsWorkUnit(
            id=f"{agg.bucket_start_time.isoformat()}-{agg.resource}",
            usageStats=UsageAggregationClass(
                bucket=int(agg.bucket_start_time.timestamp()),
                duration=self.config.bucket_duration,
                resource=_table_ref_to_urn(agg.resource, self.config.env),
                metrics=UsageAggregationMetricsClass(
                    users=[
                        UsersUsageCountsClass(
                            user=builder.UNKNOWN_USER,
                            count=count,
                            user_email=user_email,
                        )
                        for user_email, count in agg.userCounts.most_common()
                    ],
                    top_sql_queries=[
                        query
                        for query, _ in agg.queryFreq.most_common(
                            self.config.top_n_queries
                        )
                    ],
                ),
            ),
        )

    def get_report(self) -> SourceReport:
        return self.report
