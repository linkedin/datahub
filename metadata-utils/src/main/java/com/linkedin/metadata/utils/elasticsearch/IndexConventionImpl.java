package com.linkedin.metadata.utils.elasticsearch;

import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.models.EntitySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;


// Default implementation of search index naming convention
public class IndexConventionImpl implements IndexConvention {
  private final Map<String, String> indexNameMapping = new HashMap<>();
  private final Optional<String> _prefix;

  public IndexConventionImpl(@Nullable String prefix) {
    _prefix = StringUtils.isEmpty(prefix) ? Optional.empty() : Optional.of(prefix);
  }

  private String createIndexName(String baseName) {
    return (_prefix.map(prefix -> prefix + "_").orElse("") + baseName).toLowerCase();
  }

  @Nonnull
  @Override
  public String getIndexName(Class<? extends RecordTemplate> documentClass) {
    return this.getIndexName(documentClass.getSimpleName());
  }

  @Nonnull
  @Override
  public String getIndexName(EntitySpec entitySpec) {
    return this.getIndexName(entitySpec.getName());
  }

  @Nonnull
  @Override
  public String getIndexName(String baseIndexName) {
    return indexNameMapping.computeIfAbsent(baseIndexName, this::createIndexName);
  }
}
