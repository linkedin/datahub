package com.linkedin.datahub.graphql.resolvers.load;

import com.linkedin.datahub.graphql.AspectLoadKey;
import com.linkedin.datahub.graphql.generated.Aspect;
import com.linkedin.datahub.graphql.generated.Entity;
import com.linkedin.datahub.graphql.resolvers.ResolverUtils;
import com.linkedin.datahub.graphql.types.aspect.AspectMapper;
import com.linkedin.metadata.aspect.AspectWithMetadata;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;


/**
 * Generic GraphQL resolver responsible for
 *
 *    1. Generating a single input AspectLoadKey.
 *    2. Resolving a single {@link Aspect}.
 *
 */
public class AspectResolver implements DataFetcher<CompletableFuture<Aspect>> {

    public AspectResolver() {
    }

    @Override
    public CompletableFuture<Aspect> get(DataFetchingEnvironment environment) {
        final DataLoader<AspectLoadKey, Aspect> loader = environment.getDataLoaderRegistry().getDataLoader("Aspect");

        String fieldName = environment.getField().getName();
        Long version = environment.getArgument("version");
        String urn = ((Entity) environment.getSource()).getUrn();

        // first, we try fetching the aspect from the local cache
        AspectWithMetadata aspectFromContext = ResolverUtils.getAspectFromLocalContext(environment);
        if (aspectFromContext != null) {
            return CompletableFuture.completedFuture(AspectMapper.map(aspectFromContext));
        }

        // if the aspect is not in the cache, we need to fetch it from GMS Aspect Resource
        return loader.load(new AspectLoadKey(urn, fieldName, version));
    }
}
