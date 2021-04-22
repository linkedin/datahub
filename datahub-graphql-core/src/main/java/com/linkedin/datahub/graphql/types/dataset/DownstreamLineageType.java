package com.linkedin.datahub.graphql.types.dataset;

import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.DownstreamEntityRelationships;
import com.linkedin.datahub.graphql.types.LoadableType;
import com.linkedin.datahub.graphql.types.relationships.mappers.DownstreamEntityRelationshipsMapper;
import com.linkedin.lineage.client.Lineages;
import com.linkedin.metadata.query.RelationshipDirection;
import com.linkedin.r2.RemoteInvocationException;

import java.net.URISyntaxException;
import java.util.List;
import java.util.stream.Collectors;

public class DownstreamLineageType implements LoadableType<DownstreamEntityRelationships> {

    private final Lineages _lineageClient;
    private final RelationshipDirection _direction = RelationshipDirection.INCOMING;

    public DownstreamLineageType(final Lineages lineageClient) {
        _lineageClient = lineageClient;
    }

    @Override
    public Class<DownstreamEntityRelationships> objectClass() {
        return DownstreamEntityRelationships.class;
    }

    @Override
    public List<DownstreamEntityRelationships> batchLoad(final List<String> keys, final QueryContext context) {

        try {
            return keys.stream().map(urn -> {
                try {
                    com.linkedin.common.EntityRelationships relationships =
                            _lineageClient.getLineage(urn, _direction);
                    return DownstreamEntityRelationshipsMapper.map(relationships);
                } catch (RemoteInvocationException | URISyntaxException e) {
                    throw new RuntimeException(String.format("Failed to batch load DownstreamLineage for dataset %s", urn), e);
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to batch load Datasets", e);
        }
    }
}
