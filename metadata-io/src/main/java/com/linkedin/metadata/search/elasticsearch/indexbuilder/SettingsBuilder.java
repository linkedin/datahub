package com.linkedin.metadata.search.elasticsearch.indexbuilder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linkedin.metadata.models.registry.SnapshotEntityRegistry;
import java.util.Map;

/**
 * Builder for generating settings for elasticsearch indices
 */
public class SettingsBuilder {

  private static final Map<String, Object> SETTINGS = buildSettings();

  private SettingsBuilder() {
  }

  public static Map<String, Object> getSettings() {
    return SETTINGS;
  }

  private static Map<String, Object> buildSettings() {
    ImmutableMap.Builder<String, Object> settings = ImmutableMap.builder();
    settings.put("max_ngram_diff", 17);
    settings.put("analysis", ImmutableMap.<String, Object>builder().put("filter", buildFilters())
        .put("tokenizer", buildTokenizers())
        .put("normalizer", buildNormalizers())
        .put("analyzer", buildAnalyzers())
        .build());
    return ImmutableMap.of("index", settings.build());
  }

  private static Map<String, Object> buildFilters() {
    ImmutableMap.Builder<String, Object> filters = ImmutableMap.builder();
    // Filter to allow partial matches on each token
    filters.put("partial_filter", ImmutableMap.<String, Object>builder().put("type", "edge_ngram")
        .put("min_gram", 3)
        .put("max_gram", 20)
        .build());

    // Filter to split string into words
    filters.put("custom_delimiter", ImmutableMap.<String, Object>builder().put("type", "word_delimiter")
        .put("split_on_numerics", false)
        .put("preserve_original", true)
        .build());

    // Filter to process URNs
    ImmutableList.Builder<String> stopWords = ImmutableList.<String>builder().add("urn").add("li");
    // Add all entity names to stop word list
    SnapshotEntityRegistry.getInstance()
        .getEntitySpecs()
        .forEach(entitySpec -> stopWords.add(entitySpec.getName().toLowerCase()));
    filters.put("urn_stop_filter",
        ImmutableMap.<String, Object>builder().put("type", "stop").put("stopwords", stopWords.build()).build());

    return filters.build();
  }

  private static Map<String, Object> buildTokenizers() {
    ImmutableMap.Builder<String, Object> tokenizers = ImmutableMap.builder();
    // Tokenize by slashes
    tokenizers.put("slash_tokenizer",
        ImmutableMap.<String, Object>builder().put("type", "pattern").put("pattern", "[/]").build());

    // Tokenize by slash, period (i.e. for tokenizing dataset name / field name), and spaces
    tokenizers.put("main_tokenizer",
        ImmutableMap.<String, Object>builder().put("type", "pattern").put("pattern", "[ ./]").build());

    // Tokenize for urns
    tokenizers.put("urn_char_group",
        ImmutableMap.<String, Object>builder().put("type", "pattern").put("pattern", "[:\\s(),]").build());

    return tokenizers.build();
  }

  // Normalizers return a single token for a given string. Suitable for keywords
  private static Map<String, Object> buildNormalizers() {
    ImmutableMap.Builder<String, Object> normalizers = ImmutableMap.builder();
    // Analyzer for partial matching (i.e. autocomplete) - Prefix matching of each token
    normalizers.put("keyword_normalizer", ImmutableMap.<String, Object>builder()
        .put("filter", ImmutableList.of("lowercase", "asciifolding"))
        .build());

    return normalizers.build();
  }

  // Analyzers turn fields into multiple tokens
  private static Map<String, Object> buildAnalyzers() {
    ImmutableMap.Builder<String, Object> analyzers = ImmutableMap.builder();
    // Analyzer for partial matching (i.e. autocomplete) - Prefix matching of each token
    analyzers.put("partial", ImmutableMap.<String, Object>builder().put("tokenizer", "main_tokenizer")
        .put("filter", ImmutableList.of("custom_delimiter", "lowercase", "partial_filter"))
        .build());

    // Analyzer for text tokenized into words (split by spaces, periods, and slashes)
    analyzers.put("word_delimited", ImmutableMap.<String, Object>builder().put("tokenizer", "main_tokenizer")
        .put("filter", ImmutableList.of("custom_delimiter", "lowercase"))
        .build());

    // Analyzer for splitting by slashes (used to get depth of browsePath)
    analyzers.put("slash_pattern", ImmutableMap.<String, Object>builder().put("tokenizer", "slash_tokenizer")
        .put("filter", ImmutableList.of("lowercase"))
        .build());

    // Analyzer for matching browse path
    analyzers.put("browse_path_hierarchy", ImmutableMap.<String, Object>builder().put("tokenizer", "path_hierarchy")
        .put("filter", ImmutableList.of("lowercase"))
        .build());

    // Analyzer for case-insensitive exact matching - Only used when building queries
    analyzers.put("custom_keyword", ImmutableMap.<String, Object>builder().put("tokenizer", "keyword")
        .put("filter", ImmutableList.of("lowercase", "asciifolding"))
        .build());

    // Analyzer for getting urn components
    analyzers.put("urn_component", ImmutableMap.<String, Object>builder().put("tokenizer", "urn_char_group")
        .put("filter", ImmutableList.of("lowercase", "urn_stop_filter"))
        .build());

    // Analyzer for partial matching urn components
    analyzers.put("partial_urn_component", ImmutableMap.<String, Object>builder().put("tokenizer", "urn_char_group")
        .put("filter", ImmutableList.of("lowercase", "urn_stop_filter", "partial_filter"))
        .build());

    return analyzers.build();
  }
}
