package com.linkedin.metadata.graph;

import com.google.common.collect.ImmutableMap;
import com.linkedin.metadata.query.Condition;
import com.linkedin.metadata.query.CriterionArray;
import com.linkedin.metadata.query.Filter;
import com.linkedin.metadata.query.RelationshipDirection;
import com.linkedin.metadata.query.RelationshipFilter;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.dao.exception.RetryLimitReached;
import com.linkedin.metadata.dao.utils.Statement;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;


public class Neo4jGraphDAO {
  private static final int MAX_TRANSACTION_RETRY = 3;
  private final Driver _driver;
  private SessionConfig _sessionConfig;
  private static Map<String, String> _urnToEntityMap = null;

  public Neo4jGraphDAO(@Nonnull Driver driver) {
    this(driver, SessionConfig.defaultConfig());
  }

  public Neo4jGraphDAO(@Nonnull Driver driver, @Nonnull SessionConfig sessionConfig) {
    this._driver = driver;
    this._sessionConfig = sessionConfig;
  }

  @Data
  @AllArgsConstructor
  public static class Edge {
    private Urn source;
    private Urn destination;
    private String relationshipType;
  }

  public void addEdge(Edge edge) {
    final String sourceType = edge.getSource().getEntityType();
    final String destinationType = edge.getDestination().getEntityType();

    final List<Statement> statements = new ArrayList<>();

    // Add/Update source & destination node first
    statements.add(getOrInsertNode(edge.getSource()));
    statements.add(getOrInsertNode(edge.getDestination()));

    // Add/Update relationship
    final String mergeRelationshipTemplate =
        "MATCH (source:%s {urn: $sourceUrn}),(destination:%s {urn: $destinationUrn}) MERGE (source)-[r:%s]->(destination) SET r = $properties";
    final String statement =
        String.format(mergeRelationshipTemplate, sourceType, destinationType, edge.getRelationshipType());

    final Map<String, Object> paramsMerge = new HashMap<>();
    paramsMerge.put("sourceUrn", edge.getSource().toString());
    paramsMerge.put("destinationUrn", edge.getDestination().toString());
    paramsMerge.put("properties", new HashMap<>());

    statements.add(buildStatement(statement, paramsMerge));

    executeStatements(statements);
  }

  @Nonnull
  public List<String> findRelatedUrns(@Nullable String sourceType, @Nonnull Filter sourceEntityFilter,
      @Nullable String destinationType, @Nonnull Filter destinationEntityFilter,
      @Nonnull List<String> relationshipTypes, @Nonnull RelationshipFilter relationshipFilter, int offset, int count) {
    final String srcCriteria = filterToCriteria(sourceEntityFilter);
    final String destCriteria = filterToCriteria(destinationEntityFilter);
    final String edgeCriteria = criterionToString(relationshipFilter.getCriteria());

    final RelationshipDirection relationshipDirection = relationshipFilter.getDirection();

    String matchTemplate = "MATCH (src%s %s)-[r:%s %s]-(dest%s %s) RETURN dest";
    if (relationshipDirection == RelationshipDirection.INCOMING) {
      matchTemplate = "MATCH (src%s %s)<-[r:%s %s]-(dest%s %s) RETURN dest";
    } else if (relationshipDirection == RelationshipDirection.OUTGOING) {
      matchTemplate = "MATCH (src%s %s)-[r:%s %s]->(dest%s %s) RETURN dest";
    }

    String statementString =
        String.format(matchTemplate, sourceType, srcCriteria, StringUtils.join(relationshipTypes, "|"), edgeCriteria,
            destinationType, destCriteria);

    statementString += " SKIP $offset LIMIT $count";

    final Statement statement = new Statement(statementString, ImmutableMap.of("offset", offset, "count", count));

    return runQuery(statement).list(record -> record.values().get(0).asNode().get("urn").asString());
  }

  @Nonnull
  public void removeNode(@Nonnull Urn urn) {
    // also delete any relationship going to or from it
    final String matchTemplate = "MATCH (node {urn: $urn}) DETACH DELETE node";
    final String statement = String.format(matchTemplate);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    runQuery(buildStatement(statement, params));
  }

  @Nonnull
  public void removeEdgeTypesFromNode(@Nonnull Urn urn, @Nonnull List<String> relationshipTypes,
      @Nonnull RelationshipFilter relationshipFilter) {

    // also delete any relationship going to or from it
    final String nodeType = urn.getEntityType();
    final RelationshipDirection relationshipDirection = relationshipFilter.getDirection();

    String matchTemplate = "MATCH (src {urn: $urn})-[r:%s]-(dest) DELETE r";
    if (relationshipDirection == RelationshipDirection.INCOMING) {
      matchTemplate = "MATCH (src {urn: $urn})<-[r:%s]-(dest) DELETE r";
    } else if (relationshipDirection == RelationshipDirection.OUTGOING) {
      matchTemplate = "MATCH (src {urn: $urn})-[r:%s]->(dest) DELETE r";
    }
    final String statement = String.format(matchTemplate, StringUtils.join(relationshipTypes, "|"));

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    runQuery(buildStatement(statement, params));
  }

  // visible for testing
  @Nonnull
  Statement buildStatement(@Nonnull String queryTemplate, @Nonnull Map<String, Object> params) {
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      String k = entry.getKey();
      Object v = entry.getValue();
      params.put(k, toPropertyValue(v));
    }
    return new Statement(queryTemplate, params);
  }

  @Nonnull
  private Object toPropertyValue(@Nonnull Object obj) {
    if (obj instanceof Urn) {
      return obj.toString();
    }
    return obj;
  }

  @AllArgsConstructor
  @Data
  private static final class ExecutionResult {
    private long tookMs;
    private int retries;
  }

  /**
   * Executes a list of statements with parameters in one transaction.
   *
   * @param statements List of statements with parameters to be executed in order
   */
  private ExecutionResult executeStatements(@Nonnull List<Statement> statements) {
    int retry = 0;
    final StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    Exception lastException;
    try (final Session session = _driver.session(_sessionConfig)) {
      do {
        try {
          session.writeTransaction(tx -> {
            for (Statement statement : statements) {
              tx.run(statement.getCommandText(), statement.getParams());
            }
            return 0;
          });
          lastException = null;
          break;
        } catch (Neo4jException e) {
          lastException = e;
        }
      } while (++retry <= MAX_TRANSACTION_RETRY);
    }

    if (lastException != null) {
      throw new RetryLimitReached(
          "Failed to execute Neo4j write transaction after " + MAX_TRANSACTION_RETRY + " retries", lastException);
    }

    stopWatch.stop();
    return new ExecutionResult(stopWatch.getTime(), retry);
  }

  /**
   * Runs a query statement with parameters and return StatementResult.
   *
   * @param statement a statement with parameters to be executed
   * @return list of elements in the query result
   */
  @Nonnull
  private Result runQuery(@Nonnull Statement statement) {
    return _driver.session(_sessionConfig).run(statement.getCommandText(), statement.getParams());
  }

  // Returns "key:value" String, if value is not primitive, then use toString() and double quote it
  @Nonnull
  private static String toCriterionString(@Nonnull String key, @Nonnull Object value) {
    if (ClassUtils.isPrimitiveOrWrapper(value.getClass())) {
      return key + ":" + value;
    }

    return key + ":\"" + value.toString() + "\"";
  }

  /**
   * Converts {@link Filter} to neo4j query criteria, filter criterion condition requires to be EQUAL.
   *
   * @param filter Query Filter
   * @return Neo4j criteria string
   */
  @Nonnull
  public static String filterToCriteria(@Nonnull Filter filter) {
    return criterionToString(filter.getCriteria());
  }

  /**
   * Converts {@link CriterionArray} to neo4j query string.
   *
   * @param criterionArray CriterionArray in a Filter
   * @return Neo4j criteria string
   */
  @Nonnull
  public static String criterionToString(@Nonnull CriterionArray criterionArray) {
    if (!criterionArray.stream().allMatch(criterion -> Condition.EQUAL.equals(criterion.getCondition()))) {
      throw new RuntimeException("Neo4j query filter only support EQUAL condition " + criterionArray);
    }

    final StringJoiner joiner = new StringJoiner(",", "{", "}");

    criterionArray.forEach(criterion -> joiner.add(toCriterionString(criterion.getField(), criterion.getValue())));

    return joiner.length() <= 2 ? "" : joiner.toString();
  }

  /**
   * Gets Node based on Urn, if not exist, creates placeholder node.
   */
  @Nonnull
  private Statement getOrInsertNode(@Nonnull Urn urn) {
    final String nodeType = urn.getEntityType();

    final String mergeTemplate = "MERGE (node:%s {urn: $urn}) RETURN node";
    final String statement = String.format(mergeTemplate, nodeType);

    final Map<String, Object> params = new HashMap<>();
    params.put("urn", urn.toString());

    return buildStatement(statement, params);
  }
}
