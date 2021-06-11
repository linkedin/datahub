from abc import ABC
import time
from typing import List, Iterable, Dict, Optional
from datahub.configuration.common import ConfigModel
from dataclasses import field
from datahub.ingestion.api.common import PipelineContext
from datahub.ingestion.source.metadata_common import MetadataWorkUnit
from datahub.ingestion.api.source import Source, SourceReport
from dataclasses import dataclass
import logging
from datahub.metadata.schema_classes import (DatasetPropertiesClass, InstitutionalMemoryClass,
                                             InstitutionalMemoryMetadataClass, AuditStampClass)
from tqdm.auto import tqdm
from collections import Counter
from datahub.ingestion.source.openapi_parser import (get_swag_json, get_tok, get_url_basepath, get_endpoints,
                                                     set_metadata, clean_url, request_call, extract_fields,
                                                     try_guessing, compose_url_attr)
from datahub.metadata.com.linkedin.pegasus2avro.metadata.snapshot import DatasetSnapshot
from datahub.metadata.com.linkedin.pegasus2avro.mxe import MetadataChangeEvent

logger: logging.Logger = logging.getLogger(__name__)


class OpenApiConfig(ConfigModel):
    name: str
    url: str
    swagger_file: str
    ignore_endpoints: Optional[list]
    username: str = ""
    password: str = ""
    forced_examples: Optional[dict]
    token: str = None

    def get_swagger(self) -> Dict:
        if self.token:  # token based authentication, to be tested
            token = get_tok(url=self.url, username=self.username, password=self.password)

            sw_dict = get_swag_json(self.url, token=token, swagger_file=self.swagger_file)  # load the swagger file
        else:
            sw_dict = get_swag_json(self.url, username=self.username, password=self.password,
                                    swagger_file=self.swagger_file)
        return sw_dict


@dataclass
class ApiWorkUnit(MetadataWorkUnit):
    pass


@dataclass
class OpenApiSourceReport(SourceReport):
    endpoint_scanned: int = 0
    filtered: List[str] = field(default_factory=list)
    stat_warnings: Counter = field(default_factory=Counter)

    def report_endpoint_scanned(self, endpoint_name: str) -> None:
        self.endpoint_scanned += 1

    def report_dropped(self, endpoint_name: str) -> None:
        self.filtered.append(endpoint_name)

    def report_stat_warnings(self, message: str):
        self.stat_warnings.update({message: 1})


class APISource(Source, ABC):

    def __init__(self, config: OpenApiConfig, ctx: PipelineContext, platform: str):
        super().__init__(ctx)
        self.config = config
        self.platform = platform
        self.report = OpenApiSourceReport()

    def report_bad_responses(self, status_code: int, key: str):
        if status_code == 400:
            self.report.report_warning(key=key, reason="Unknown error for reaching endpoint")
        elif status_code == 403:
            self.report.report_warning(key=key, reason="Not authorised to get endpoint")
        elif status_code == 404:
            self.report.report_warning(key=key, reason=f"Unable to find an example for {key}"
                                                       f" in. Please add it to the list of forced examples.")
        elif status_code == 500:
            self.report.report_warning(key=key, reason="Server error for reaching endpoint")
        elif status_code == 504:
            self.report.report_warning(key=key, reason="Timeout for reaching endpoint")
        else:
            raise Exception(f"Unable to retrieve {key}, response code {status_code}")

    def get_workunits(self) -> Iterable[ApiWorkUnit]:
        config = self.config

        sw_dict = config.get_swagger()

        url_basepath = get_url_basepath(sw_dict)

        # Getting all the URLs accepting the "GET" method,
        # together with their description and the tags
        url_endpoints = get_endpoints(sw_dict)

        # here we put a sample from the listing of urls. To be used for later guessing of comosed urls.
        root_dataset_samples = {}

        # looping on all the urls. Each URL will be a dataset, for our definition
        for endpoint_k, endpoint_dets in tqdm(url_endpoints.items(), desc="Checking urls..."):
            if endpoint_k in config.ignore_endpoints:
                continue

            dataset_name = endpoint_k[1:].replace("/", ".")
            if dataset_name[-1] == ".":
                dataset_name = dataset_name[:-1]

            dataset_snapshot = DatasetSnapshot(
                urn=f"urn:li:dataset:(urn:li:dataPlatform:{self.platform},{config.name}.{dataset_name},PROD)",
                aspects=[],
            )
            dataset_properties = DatasetPropertiesClass(
                description=endpoint_dets["description"],
                customProperties={},
                tags=endpoint_dets["tags"]
            )
            dataset_snapshot.aspects.append(dataset_properties)

            link_url = clean_url(config.url + url_basepath + endpoint_k)
            link_description = "Link to call for the dataset."
            creation = AuditStampClass(time=int(time.time()),
                                       actor="urn:li:corpuser:etl",
                                       impersonator=None)
            link_metadata = InstitutionalMemoryMetadataClass(url=link_url, description=link_description,
                                                             createStamp=creation)
            inst_memory = InstitutionalMemoryClass([link_metadata])
            dataset_snapshot.aspects.append(inst_memory)

            if "data" in endpoint_dets.keys():
                # we are lucky! data is defined in the swagger for this endpoint
                schema_metadata = set_metadata(dataset_name, endpoint_dets["data"])
                dataset_snapshot.aspects.append(schema_metadata)

                mce = MetadataChangeEvent(proposedSnapshot=dataset_snapshot)
                wu = ApiWorkUnit(id=dataset_name, mce=mce)
                self.report.report_workunit(wu)
                yield wu
            # elif "parameters" in endpoint_dets.keys():
            #     # half of a luck: we have explicitely declared parameters
            #     enp_ex_pars = ""
            #     for param_def in endpoint_dets["parameters"]:
            #         enp_ex_pars += ""
            elif "{" not in endpoint_k:  # if the API does not explicitely require parameters
                tot_url = clean_url(config.url + url_basepath + endpoint_k)
                if config.token:
                    # to be implemented
                    # response = request_call(tot_url, token=token)
                    raise Exception("You should implement this!")
                else:
                    response = request_call(tot_url, username=config.username, password=config.password)
                if response.status_code == 200:
                    fields2add, root_dataset_samples[dataset_name] = extract_fields(response, dataset_name)
                    if not fields2add:
                        self.report.report_warning(key=endpoint_k, reason="No Fields")
                    schema_metadata = set_metadata(dataset_name, fields2add)
                    dataset_snapshot.aspects.append(schema_metadata)

                    mce = MetadataChangeEvent(proposedSnapshot=dataset_snapshot)
                    wu = ApiWorkUnit(id=dataset_name, mce=mce)
                    self.report.report_workunit(wu)
                    yield wu
                else:
                    self.report_bad_responses(response.status_code, key=endpoint_k)
            else:
                if endpoint_k not in config.forced_examples.keys():
                    # start guessing...
                    url_guess = try_guessing(endpoint_k, root_dataset_samples)  # try to guess informations
                    tot_url = clean_url(config.url + url_basepath + url_guess)
                    if config.token:
                        # response = request_call(tot_url, token=token)
                        raise Exception("You should implement this!")
                    else:
                        response = request_call(tot_url, username=config.username, password=config.password)
                    if response.status_code == 200:
                        fields2add, _ = extract_fields(response, dataset_name)
                        if not fields2add:
                            self.report.report_warning(key=endpoint_k, reason="No Fields")
                        schema_metadata = set_metadata(dataset_name, fields2add)
                        dataset_snapshot.aspects.append(schema_metadata)

                        mce = MetadataChangeEvent(proposedSnapshot=dataset_snapshot)
                        wu = ApiWorkUnit(id=dataset_name, mce=mce)
                        self.report.report_workunit(wu)
                        yield wu
                    else:
                        self.report_bad_responses(response.status_code, key=endpoint_k)
                else:
                    composed_url = compose_url_attr(raw_url=endpoint_k, attr_list=config.forced_examples[endpoint_k])
                    tot_url = clean_url(config.url + url_basepath + composed_url)
                    if config.token:
                        # response = request_call(tot_url, token=token)
                        raise Exception("You should implement this!")
                    else:
                        response = request_call(tot_url, username=config.username, password=config.password)
                    if response.status_code == 200:
                        fields2add, _ = extract_fields(response, dataset_name)
                        if not fields2add:
                            self.report.report_warning(key=endpoint_k, reason="No Fields")
                        schema_metadata = set_metadata(dataset_name, fields2add)
                        dataset_snapshot.aspects.append(schema_metadata)

                        mce = MetadataChangeEvent(proposedSnapshot=dataset_snapshot)
                        wu = ApiWorkUnit(id=dataset_name, mce=mce)
                        self.report.report_workunit(wu)
                        yield wu
                    else:
                        self.report_bad_responses(response.status_code, key=endpoint_k)



        # for schema in inspector.get_schema_names():
        #     if not sql_config.schema_pattern.allowed(schema):
        #         self.report.report_dropped(schema)
        #         continue
        #
        #     for table in inspector.get_table_names(schema):
        #         schema, table = sql_config.standardize_schema_table_names(schema, table)
        #         dataset_name = sql_config.get_identifier(schema, table)
        #         self.report.report_table_scanned(dataset_name)
        #
        #         if not sql_config.table_pattern.allowed(dataset_name):
        #             self.report.report_dropped(dataset_name)
        #             continue
        #
        #         columns = inspector.get_columns(table, schema)
        #         try:
        #             table_info: dict = inspector.get_table_comment(table, schema)
        #         except NotImplementedError:
        #             description: Optional[str] = None
        #             properties: Dict[str, str] = {}
        #         else:
        #             description = table_info["text"]
        #
        #             # The "properties" field is a non-standard addition to SQLAlchemy's interface.
        #             properties = table_info.get("properties", {})
        #
        #         # TODO: capture inspector.get_pk_constraint
        #         # TODO: capture inspector.get_sorted_table_and_fkc_names
        #
        #         dataset_snapshot = DatasetSnapshot(
        #             urn=f"urn:li:dataset:(urn:li:dataPlatform:{self.platform},{dataset_name},{self.config.env})",
        #             aspects=[],
        #         )
        #         if description is not None or properties:
        #             dataset_properties = DatasetPropertiesClass(
        #                 description=description,
        #                 customProperties=properties,
        #                 # uri=dataset_name,
        #             )
        #             dataset_snapshot.aspects.append(dataset_properties)
        #         schema_metadata = get_schema_metadata(
        #             self.report, dataset_name, self.platform, columns
        #         )
        #         dataset_snapshot.aspects.append(schema_metadata)
        #
        #         mce = MetadataChangeEvent(proposedSnapshot=dataset_snapshot)
        #         wu = SqlWorkUnit(id=dataset_name, mce=mce)
        #         self.report.report_workunit(wu)
        #         yield wu

    def get_report(self):
        return self.report

    def close(self):
        pass


class OpenApiSource(APISource):
    def __init__(self, config: OpenApiConfig, ctx: PipelineContext):
        super().__init__(config, ctx, "api")

    @classmethod
    def create(cls, config_dict, ctx):
        config = OpenApiConfig.parse_obj(config_dict)
        return cls(config, ctx)
