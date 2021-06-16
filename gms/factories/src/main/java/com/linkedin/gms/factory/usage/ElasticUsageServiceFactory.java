package com.linkedin.gms.factory.usage;

import com.linkedin.gms.factory.common.IndexConventionFactory;
import com.linkedin.gms.factory.common.RestHighLevelClientFactory;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.models.registry.SnapshotEntityRegistry;
import com.linkedin.metadata.search.elasticsearch.ElasticSearchService;
import com.linkedin.metadata.search.elasticsearch.indexbuilder.ESIndexBuilders;
import com.linkedin.metadata.search.elasticsearch.query.ESBrowseDAO;
import com.linkedin.metadata.search.elasticsearch.query.ESSearchDAO;
import com.linkedin.metadata.search.elasticsearch.update.ESWriteDAO;
import com.linkedin.metadata.utils.elasticsearch.IndexConvention;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.annotation.Nonnull;


@Configuration
@Import({RestHighLevelClientFactory.class, IndexConventionFactory.class})
public class ElasticSearchServiceFactory {
  @Autowired
  @Qualifier("elasticSearchRestHighLevelClient")
  private RestHighLevelClient searchClient;

  @Autowired
  @Qualifier(IndexConventionFactory.INDEX_CONVENTION_BEAN)
  private IndexConvention indexConvention;

  @Value("${ES_BULK_REQUESTS_LIMIT:1}")
  private Integer bulkRequestsLimit;

  @Value("${ES_BULK_FLUSH_PERIOD:1}")
  private Integer bulkFlushPeriod;

  @Value("${ES_BULK_NUM_RETRIES:3}")
  private Integer numRetries;

  @Value("${ES_BULK_RETRY_INTERVAL:1}")
  private Long retryInterval;

  @Bean(name = "elasticSearchService")
  @Nonnull
  protected ElasticSearchService getInstance() {
    EntityRegistry entityRegistry = SnapshotEntityRegistry.getInstance();
    return new ElasticSearchService(new ESIndexBuilders(entityRegistry, searchClient, indexConvention),
        new ESSearchDAO(entityRegistry, searchClient, indexConvention),
        new ESBrowseDAO(entityRegistry, searchClient, indexConvention),
        new ESWriteDAO(entityRegistry, searchClient, indexConvention, bulkRequestsLimit, bulkFlushPeriod, numRetries,
            retryInterval));
  }
}
