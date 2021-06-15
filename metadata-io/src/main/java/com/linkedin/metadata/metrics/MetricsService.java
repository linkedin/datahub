package com.linkedin.metadata.metrics;

import javax.annotation.Nonnull;


public interface MetricsService {

  void configure();

  /**
   * Write an aggregated usage metric bucket.
   * TODO this
   *
   * @param document the document to update / insert
   * @param docId the ID of the document
   */
  void upsertDocument(@Nonnull String document, @Nonnull String docId);

}
