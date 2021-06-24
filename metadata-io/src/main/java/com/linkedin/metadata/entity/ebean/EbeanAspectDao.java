package com.linkedin.metadata.entity.ebean;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.metadata.entity.AspectStorageValidationUtil;
import com.linkedin.metadata.entity.ListResult;
import com.linkedin.metadata.dao.exception.ModelConversionException;
import com.linkedin.metadata.dao.exception.RetryLimitReached;
import com.linkedin.metadata.dao.retention.IndefiniteRetention;
import com.linkedin.metadata.dao.retention.Retention;
import com.linkedin.metadata.dao.retention.TimeBasedRetention;
import com.linkedin.metadata.dao.retention.VersionBasedRetention;
import com.linkedin.metadata.dao.utils.QueryUtils;
import com.linkedin.metadata.query.ExtraInfo;
import com.linkedin.metadata.query.ExtraInfoArray;
import com.linkedin.metadata.query.ListResultMetadata;
import io.ebean.DuplicateKeyException;
import io.ebean.EbeanServer;
import io.ebean.EbeanServerFactory;
import io.ebean.PagedList;
import io.ebean.Query;
import io.ebean.RawSql;
import io.ebean.RawSqlBuilder;
import io.ebean.Transaction;
import io.ebean.config.ServerConfig;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.RollbackException;
import javax.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.metadata.entity.EntityService.*;


public class EbeanAspectDao {

  private static final Logger _logger = LoggerFactory.getLogger(EbeanAspectDao.class.getName());

  public static final String EBEAN_MODEL_PACKAGE = EbeanAspectV2.class.getPackage().getName();
  private static final IndefiniteRetention INDEFINITE_RETENTION = new IndefiniteRetention();

  private final EbeanServer _server;
  private boolean _connectionValidated = false;
  private final Map<String, Retention> _aspectRetentionMap = new HashMap<>();
  private final Clock _clock = Clock.systemUTC();

  private int _queryKeysCount = 0; // 0 means no pagination on keys

  /**
   * Constructor for EntityEbeanDao.
   *
   * @param serverConfig {@link ServerConfig} that defines the configuration of EbeanServer instances
   */
  public EbeanAspectDao(@Nonnull final ServerConfig serverConfig) {
    this(createServer(serverConfig));
  }

  @VisibleForTesting
  public EbeanAspectDao(@Nonnull final EbeanServer server) {
    _server = server;
  }

  public void setWritable() {
    _canWrite = true;
  }

  /**
   * Return the {@link EbeanServer} server instance used for customized queries.
   */
  public EbeanServer getServer() {
    return _server;
  }

  // Flag used to make sure the dao isn't writing aspects
  // while its storage is being migrated
  private boolean _canWrite = true;

  public void setConnectionValidated(boolean validated) {
    _connectionValidated = validated;
    _canWrite = validated;
  }

  private boolean validateConnection() {
    if (_connectionValidated) {
      return true;
    }
    if (!AspectStorageValidationUtil.checkV2TableExists(_server)) {
      _logger.error("GMS is on a newer version than your storage layer. Please refer to "
                    + "https://datahubproject.io/docs/advanced/no-code-upgrade to view the upgrade guide.");
      _canWrite = false;
      return false;
    } else {
      _connectionValidated = true;
      return true;
    }
  }

  public long saveLatestAspect(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nullable final String oldAspectMetadata,
      @Nullable final String oldActor,
      @Nullable final String oldImpersonator,
      @Nullable final Timestamp oldTime,
      @Nonnull final String newAspectMetadata,
      @Nonnull final String newActor,
      @Nullable final String newImpersonator,
      @Nonnull final Timestamp newTime) {
    validateConnection();
    if (!_canWrite) {
      return 0;
    }
    // Save oldValue as the largest version + 1
    long largestVersion = 0;
    if (oldAspectMetadata != null && oldTime != null) {
      largestVersion = getNextVersion(urn, aspectName);
      saveAspect(urn, aspectName, oldAspectMetadata, oldActor, oldImpersonator, oldTime, largestVersion, true);
    }

    // Save newValue as the latest version (v0)
    saveAspect(urn, aspectName, newAspectMetadata, newActor, newImpersonator, newTime, LATEST_ASPECT_VERSION, oldAspectMetadata == null);

    // Apply retention policy
    applyRetention(urn, aspectName, getRetention(aspectName), largestVersion);

    return largestVersion;
  }

  protected void saveAspect(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nonnull final String aspectMetadata,
      @Nonnull final String actor,
      @Nullable final String impersonator,
      @Nonnull final Timestamp timestamp,
      final long version,
      final boolean insert) {
    validateConnection();

    final EbeanAspectV2 aspect = new EbeanAspectV2();
    aspect.setKey(new EbeanAspectV2.PrimaryKey(urn, aspectName, version));
    aspect.setMetadata(aspectMetadata);
    aspect.setCreatedOn(timestamp);
    aspect.setCreatedBy(actor);
    if (impersonator != null) {
      aspect.setCreatedFor(impersonator);
    }

    saveAspect(aspect, insert);
  }

  protected void saveAspect(@Nonnull final EbeanAspectV2 ebeanAspect, final boolean insert) {
    validateConnection();
    if (insert) {
      _server.insert(ebeanAspect);
    } else {
      _server.update(ebeanAspect);
    }
  }

  @Nullable
  protected EbeanAspectV2 getLatestAspect(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    final EbeanAspectV2.PrimaryKey key = new EbeanAspectV2.PrimaryKey(urn, aspectName, 0L);
    return _server.find(EbeanAspectV2.class, key);
  }

  @Nullable
  public long getMaxVersion(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    List<EbeanAspectV2> result = _server.find(EbeanAspectV2.class)
        .where()
        .eq("urn", urn).eq("aspect", aspectName)
        .orderBy()
        .desc("version")
        .findList();
    if (result.size() == 0) {
      return -1;
    }
    return result.get(0).getKey().getVersion();
  }

  @Nullable
  public EbeanAspectV2 getAspect(@Nonnull final String urn, @Nonnull final String aspectName, final long version) {
    validateConnection();
    return getAspect(new EbeanAspectV2.PrimaryKey(urn, aspectName, version));
  }

  @Nullable
  public EbeanAspectV2 getAspect(@Nonnull final EbeanAspectV2.PrimaryKey primaryKey) {
    validateConnection();
    return _server.find(EbeanAspectV2.class, primaryKey);
  }

  @Nonnull
  public Map<EbeanAspectV2.PrimaryKey, EbeanAspectV2> batchGet(@Nonnull final Set<EbeanAspectV2.PrimaryKey> keys) {
    validateConnection();
    if (keys.isEmpty()) {
      return Collections.emptyMap();
    }

    final List<EbeanAspectV2> records;
    if (_queryKeysCount == 0) {
      records = batchGet(keys, keys.size());
    } else {
      records = batchGet(keys, _queryKeysCount);
    }
    return records.stream().collect(Collectors.toMap(EbeanAspectV2::getKey, record -> record));
  }

  /**
   * BatchGet that allows pagination on keys to avoid large queries.
   * TODO: can further improve by running the sub queries in parallel
   *
   * @param keys a set of keys with urn, aspect and version
   * @param keysCount the max number of keys for each sub query
   */
  @Nonnull
  private List<EbeanAspectV2> batchGet(@Nonnull final Set<EbeanAspectV2.PrimaryKey> keys, final int keysCount) {
    validateConnection();

    int position = 0;

    final int totalPageCount = QueryUtils.getTotalPageCount(keys.size(), keysCount);
    final List<EbeanAspectV2> finalResult = batchGetUnion(new ArrayList<>(keys), keysCount, position);

    while (QueryUtils.hasMore(position, keysCount, totalPageCount)) {
      position += keysCount;
      final List<EbeanAspectV2> oneStatementResult = batchGetUnion(new ArrayList<>(keys), keysCount, position);
      finalResult.addAll(oneStatementResult);
    }

    return finalResult;
  }

  /**
   * Builds a single SELECT statement for batch get, which selects one entity, and then can be UNION'd with other SELECT
   * statements.
   */
  private String batchGetSelect(
      final int selectId,
      @Nonnull final String urn,
      @Nonnull final String aspect,
      final long version,
      @Nonnull final Map<String, Object> outputParamsToValues) {
    validateConnection();

    final String urnArg = "urn" + selectId;
    final String aspectArg = "aspect" + selectId;
    final String versionArg = "version" + selectId;

    outputParamsToValues.put(urnArg, urn);
    outputParamsToValues.put(aspectArg, aspect);
    outputParamsToValues.put(versionArg, version);

    return String.format("SELECT urn, aspect, version, metadata, createdOn, createdBy, createdFor "
            + "FROM %s WHERE urn = :%s AND aspect = :%s AND version = :%s",
        EbeanAspectV2.class.getAnnotation(Table.class).name(), urnArg, aspectArg, versionArg);
  }

  @Nonnull
  private List<EbeanAspectV2> batchGetUnion(
      @Nonnull final List<EbeanAspectV2.PrimaryKey> keys,
      final int keysCount,
      final int position) {
    validateConnection();

    // Build one SELECT per key and then UNION ALL the results. This can be much more performant than OR'ing the
    // conditions together. Our query will look like:
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect0' AND version = 0
    //   UNION ALL
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect1' AND version = 0
    //   ...
    // Note: UNION ALL should be safe and more performant than UNION. We're selecting the entire entity key (as well
    // as data), so each result should be unique. No need to deduplicate.
    // Another note: ebean doesn't support UNION ALL, so we need to manually build the SQL statement ourselves.
    final StringBuilder sb = new StringBuilder();
    final int end = Math.min(keys.size(), position + keysCount);
    final Map<String, Object> params = new HashMap<>();
    for (int index = position; index < end; index++) {
      sb.append(batchGetSelect(
          index - position,
          keys.get(index).getUrn(),
          keys.get(index).getAspect(),
          keys.get(index).getVersion(),
          params));

      if (index != end - 1) {
        sb.append(" UNION ALL ");
      }
    }

    final RawSql rawSql = RawSqlBuilder.parse(sb.toString())
        .columnMapping(EbeanAspectV2.URN_COLUMN, "key.urn")
        .columnMapping(EbeanAspectV2.ASPECT_COLUMN, "key.aspect")
        .columnMapping(EbeanAspectV2.VERSION_COLUMN, "key.version")
        .create();

    final Query<EbeanAspectV2> query = _server.find(EbeanAspectV2.class).setRawSql(rawSql);

    for (Map.Entry<String, Object> param : params.entrySet()) {
      query.setParameter(param.getKey(), param.getValue());
    }

    return query.findList();
  }

  @Nonnull
  public ListResult<Long> listVersions(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {
    validateConnection();

    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.KEY_ID)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn)
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.VERSION_COLUMN)
        .findPagedList();

    List<Long> versions = pagedList.getList().stream().map(a -> a.getKey().getVersion()).collect(Collectors.toList());
    return toListResult(versions, null, pagedList, start);
  }

  @Nonnull
  public ListResult<String> listUrns(
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {
    validateConnection();

    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.KEY_ID)
        .where()
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .eq(EbeanAspectV2.VERSION_COLUMN, LATEST_ASPECT_VERSION)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.URN_COLUMN)
        .findPagedList();

    final List<String> urns = pagedList
        .getList()
        .stream()
        .map(entry -> entry.getKey().getUrn())
        .collect(Collectors.toList());

    return toListResult(urns, null, pagedList, start);
  }

  @Nonnull
  public ListResult<String> listAspectMetadata(
      @Nonnull final Urn urn,
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {
    validateConnection();

    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.ALL_COLUMNS)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn.toString())
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.VERSION_COLUMN)
        .findPagedList();

    final List<String> aspects = pagedList.getList().stream().map(EbeanAspectV2::getMetadata).collect(Collectors.toList());
    final ListResultMetadata listResultMetadata = toListResultMetadata(pagedList.getList().stream().map(
        EbeanAspectDao::toExtraInfo).collect(Collectors.toList()));
    return toListResult(aspects, listResultMetadata, pagedList, start);
  }

  @Nonnull
  public ListResult<String> listLatestAspectMetadata(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {
    return listAspectMetadata(entityName, aspectName, LATEST_ASPECT_VERSION, start, pageSize);
  }

  @Nonnull
  public ListResult<String> listAspectMetadata(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      final long version,
      final int start,
      final int pageSize) {
    validateConnection();

    final String urnPrefixMatcher = "urn:li:" + entityName + ":%";
    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.ALL_COLUMNS)
        .where()
        .like(EbeanAspectV2.URN_COLUMN, urnPrefixMatcher)
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .eq(EbeanAspectV2.VERSION_COLUMN, version)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.URN_COLUMN)
        .findPagedList();

    final List<String> aspects = pagedList.getList().stream().map(EbeanAspectV2::getMetadata).collect(Collectors.toList());
    final ListResultMetadata listResultMetadata = toListResultMetadata(pagedList.getList().stream().map(
        EbeanAspectDao::toExtraInfo).collect(Collectors.toList()));
    return toListResult(aspects, listResultMetadata, pagedList, start);
  }

  @Nonnull
  public Retention getRetention(@Nonnull final String aspectName) {
    return _aspectRetentionMap.getOrDefault(aspectName, INDEFINITE_RETENTION);
  }

  public void setRetention(@Nonnull final String aspectName, @Nonnull final Retention retention) {
    _aspectRetentionMap.put(aspectName, retention);
  }

  @Nonnull
  public <T> T runInTransactionWithRetry(@Nonnull final Supplier<T> block, final int maxTransactionRetry) {
    validateConnection();
    int retryCount = 0;
    Exception lastException;

    T result = null;
    do {
      try (Transaction transaction = _server.beginTransaction()) {
        result = block.get();
        transaction.commit();
        lastException = null;
        break;
      } catch (RollbackException | DuplicateKeyException exception) {
        lastException = exception;
      }
    } while (++retryCount <= maxTransactionRetry);

    if (lastException != null) {
      throw new RetryLimitReached("Failed to add after " + maxTransactionRetry + " retries", lastException);
    }

    return result;
  }


  private void applyRetention(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nonnull final Retention retention,
      long largestVersion) {
    if (retention instanceof IndefiniteRetention) {
      return;
    }

    if (retention instanceof VersionBasedRetention) {
      applyVersionBasedRetention(urn, aspectName, (VersionBasedRetention) retention, largestVersion);
      return;
    }

    if (retention instanceof TimeBasedRetention) {
      applyTimeBasedRetention(urn, aspectName, (TimeBasedRetention) retention, _clock.millis());
      return;
    }
  }

  protected void applyVersionBasedRetention(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nonnull final VersionBasedRetention retention,
      long largestVersion) {
    validateConnection();

    _server.find(EbeanAspectV2.class)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn)
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .ne(EbeanAspectV2.VERSION_COLUMN, LATEST_ASPECT_VERSION)
        .le(EbeanAspectV2.VERSION_COLUMN, largestVersion - retention.getMaxVersionsToRetain() + 1)
        .delete();
  }

  protected void applyTimeBasedRetention(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nonnull final TimeBasedRetention retention,
      long currentTime) {
    validateConnection();

    _server.find(EbeanAspectV2.class)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn.toString())
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .lt(EbeanAspectV2.CREATED_ON_COLUMN, new Timestamp(currentTime - retention.getMaxAgeToRetain()))
        .delete();
  }

  private long getNextVersion(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    final List<EbeanAspectV2.PrimaryKey> result = _server.find(EbeanAspectV2.class)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn.toString())
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .orderBy()
        .desc(EbeanAspectV2.VERSION_COLUMN)
        .setMaxRows(1)
        .findIds();

    return result.isEmpty() ? 0 : result.get(0).getVersion() + 1L;
  }

  @Nonnull
  private <T> ListResult<T> toListResult(
      @Nonnull final List<T> values,
      @Nullable final ListResultMetadata listResultMetadata,
      @Nonnull final PagedList<?> pagedList,
      @Nullable final Integer start) {
    final int nextStart =
        (start != null && pagedList.hasNext()) ? start + pagedList.getList().size() : ListResult.INVALID_NEXT_START;
    return ListResult.<T>builder()
        // Format
        .values(values)
        .metadata(listResultMetadata)
        .nextStart(nextStart)
        .hasNext(pagedList.hasNext())
        .totalCount(pagedList.getTotalCount())
        .totalPageCount(pagedList.getTotalPageCount())
        .pageSize(pagedList.getPageSize())
        .build();
  }

  @Nonnull
  private static ExtraInfo toExtraInfo(@Nonnull final EbeanAspectV2 aspect) {
    final ExtraInfo extraInfo = new ExtraInfo();
    extraInfo.setVersion(aspect.getKey().getVersion());
    extraInfo.setAudit(toAuditStamp(aspect));
    try {
      extraInfo.setUrn(Urn.createFromString(aspect.getKey().getUrn()));
    } catch (URISyntaxException e) {
      throw new ModelConversionException(e.getMessage());
    }

    return extraInfo;
  }

  @Nonnull
  private static AuditStamp toAuditStamp(@Nonnull final EbeanAspectV2 aspect) {
    final AuditStamp auditStamp = new AuditStamp();
    auditStamp.setTime(aspect.getCreatedOn().getTime());

    try {
      auditStamp.setActor(new Urn(aspect.getCreatedBy()));
      if (aspect.getCreatedFor() != null) {
        auditStamp.setImpersonator(new Urn(aspect.getCreatedFor()));
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    return auditStamp;
  }

  @Nonnull
  private ListResultMetadata toListResultMetadata(@Nonnull final List<ExtraInfo> extraInfos) {
    final ListResultMetadata listResultMetadata = new ListResultMetadata();
    listResultMetadata.setExtraInfos(new ExtraInfoArray(extraInfos));
    return listResultMetadata;
  }

  @Nonnull
  private static EbeanServer createServer(@Nonnull final ServerConfig serverConfig) {
    // Make sure that the serverConfig includes the package that contains DAO's Ebean model.
    if (!serverConfig.getPackages().contains(EBEAN_MODEL_PACKAGE)) {
      serverConfig.getPackages().add(EBEAN_MODEL_PACKAGE);
    }
    // TODO: Consider supporting SCSI
    return EbeanServerFactory.create(serverConfig);
  }
}
