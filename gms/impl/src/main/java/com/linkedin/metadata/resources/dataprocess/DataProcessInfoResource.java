package com.linkedin.metadata.resources.dataprocess;

import com.google.common.collect.ImmutableSet;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.dataprocess.DataProcessInfo;
import com.linkedin.metadata.EntitySpecUtils;
import com.linkedin.metadata.restli.RestliUtils;
import com.linkedin.parseq.Task;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.annotations.Optional;
import com.linkedin.restli.server.annotations.QueryParam;
import com.linkedin.restli.server.annotations.RestLiCollection;
import com.linkedin.restli.server.annotations.RestMethod;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Deprecated! Use {@link EntityResource} instead.
 */
@RestLiCollection(name = "dataProcessInfo", namespace = "com.linkedin.dataprocess", parent = DataProcesses.class)
public class DataProcessInfoResource extends BaseDataProcessesAspectResource<DataProcessInfo> {

    public DataProcessInfoResource() {
        super(DataProcessInfo.class);
    }

    @Nonnull
    @Override
    @RestMethod.Create
    public Task<CreateResponse> create(@Nonnull DataProcessInfo dataProcessInfo) {
        return RestliUtils.toTask(() -> {
            final Urn urn = getUrn(getContext().getPathKeys());
            final AuditStamp auditStamp = getAuditor().requestAuditStamp(getContext().getRawRequestContext());
            getEntityService().ingestAspect(
                urn,
                EntitySpecUtils.getAspectNameFromSchema(dataProcessInfo.schema()),
                dataProcessInfo,
                auditStamp);
            return new CreateResponse(HttpStatus.S_201_CREATED);
        });
    }

    @Nonnull
    @Override
    @RestMethod.Get
    public Task<DataProcessInfo> get(@QueryParam("version") @Optional("0") @Nonnull Long version) {
        return RestliUtils.toTask(() -> {
            final Urn urn = getUrn(getContext().getPathKeys());
            final RecordDataSchema aspectSchema = new DataProcessInfo().schema();

            final Map<Urn, List<RecordTemplate>> urnToAspectsMap = getEntityService().batchGetAspectRecordLists(
                ImmutableSet.of(urn),
                ImmutableSet.of(EntitySpecUtils.getAspectNameFromSchema(aspectSchema))
            );

            if (urnToAspectsMap.containsKey(urn)) {
                // Aspect does exist.
                final RecordTemplate aspect = urnToAspectsMap.get(urn).stream()
                    .filter(aspectRecord -> aspectRecord.schema().getFullName().equals(aspectSchema.getFullName()))
                    .findFirst()
                    .get();
                return new DataProcessInfo(aspect.data());
            }
            throw RestliUtils.resourceNotFoundException();
        });
    }
}
