/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.embedded.repo;

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.agent.collector.Collector.AggregateReader;
import org.glowroot.agent.collector.Collector.AggregateVisitor;
import org.glowroot.agent.embedded.repo.model.Stored;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.RowMappers;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.repo.util.ThreadStatsCreator;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    private static final String AGENT_ID = "";

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_duration_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("async_transactions", ColumnType.BOOLEAN),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("service_calls_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("async_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("main_thread_total_cpu_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_blocked_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_waited_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_allocated_bytes", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_cpu_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_blocked_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_waited_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_allocated_bytes", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("duration_nanos_histogram", ColumnType.VARBINARY)); // protobuf

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_duration_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("async_transactions", ColumnType.BOOLEAN),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("service_calls_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("main_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("aux_thread_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("async_root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("main_thread_total_cpu_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_blocked_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_waited_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("main_thread_total_allocated_bytes", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_cpu_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_blocked_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_waited_nanos", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("aux_thread_total_allocated_bytes", ColumnType.DOUBLE), // nullable
                    ImmutableColumn.of("duration_nanos_histogram", ColumnType.VARBINARY)); // protobuf

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "total_duration_nanos",
                    "transaction_count", "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name",
                    "total_duration_nanos", "transaction_count", "error_count");

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final ConfigRepositoryImpl configRepository;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;

    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            ConfigRepositoryImpl configRepository, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao) throws Exception {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.configRepository = configRepository;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;

        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            String overallTableName = "aggregate_tt_rollup_" + castUntainted(i);
            dataSource.syncTable(overallTableName, overallAggregatePointColumns);
            dataSource.syncIndexes(overallTableName, ImmutableList.<Index>of(
                    ImmutableIndex.of(overallTableName + "_idx", overallAggregateIndexColumns)));
            String transactionTableName = "aggregate_tn_rollup_" + castUntainted(i);
            dataSource.syncTable(transactionTableName, transactionAggregateColumns);
            dataSource.syncIndexes(transactionTableName, ImmutableList.<Index>of(ImmutableIndex
                    .of(transactionTableName + "_idx", transactionAggregateIndexColumns)));
        }

        // don't need last_rollup_times table like in GaugeValueDao since there is already index
        // on capture_time so these queries are relatively fast
        long[] lastRollupTimes = new long[rollupConfigs.size()];
        lastRollupTimes[0] = 0;
        for (int i = 1; i < lastRollupTimes.length; i++) {
            lastRollupTimes[i] = dataSource.queryForLong("select ifnull(max(capture_time), 0)"
                    + " from aggregate_tt_rollup_" + castUntainted(i));
        }
        this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    public void store(AggregateReader aggregateReader) throws Exception {
        final long captureTime = aggregateReader.captureTime();
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        final CappedDatabase cappedDatabase = rollupCappedDatabases.get(0);
        final List<TruncatedQueryText> truncatedQueryTexts = Lists.newArrayList();
        aggregateReader.accept(new AggregateVisitor() {
            @Override
            public void visitOverallAggregate(String transactionType, List<String> sharedQueryTexts,
                    Aggregate overallAggregate) throws Exception {
                addToTruncatedQueryTexts(sharedQueryTexts);
                dataSource.update(new AggregateInsert(transactionType, null, captureTime,
                        overallAggregate, truncatedQueryTexts, 0, cappedDatabase));
                transactionTypeDao.updateLastCaptureTime(transactionType, captureTime);
            }
            @Override
            public void visitTransactionAggregate(String transactionType, String transactionName,
                    List<String> sharedQueryTexts, Aggregate transactionAggregate)
                    throws Exception {
                addToTruncatedQueryTexts(sharedQueryTexts);
                dataSource.update(new AggregateInsert(transactionType, transactionName, captureTime,
                        transactionAggregate, truncatedQueryTexts, 0, cappedDatabase));
            }
            private void addToTruncatedQueryTexts(List<String> sharedQueryTexts)
                    throws SQLException {
                for (String sharedQueryText : sharedQueryTexts) {
                    String truncatedText;
                    String fullTextSha1;
                    if (sharedQueryText.length() > StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                        truncatedText = sharedQueryText.substring(0,
                                StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE);
                        fullTextSha1 = fullQueryTextDao.updateLastCaptureTime(sharedQueryText,
                                captureTime);
                    } else {
                        truncatedText = sharedQueryText;
                        fullTextSha1 = null;
                    }
                    truncatedQueryTexts
                            .add(ImmutableTruncatedQueryText.of(truncatedText, fullTextSha1));
                }
            }
        });
        synchronized (rollupLock) {
            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                RollupConfig rollupConfig = rollupConfigs.get(i);
                long safeRollupTime = RollupLevelService.getSafeRollupTime(captureTime,
                        rollupConfig.intervalMillis());
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, rollupConfig.intervalMillis(), i, i - 1);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollupId, OverallQuery query,
            OverallSummaryCollector collector) throws Exception {
        dataSource.query(new OverallSummaryQuery(collector, query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionSummariesInto(String agentRollupId, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector)
            throws Exception {
        dataSource.query(
                new TransactionSummaryQuery(query, sortOrder, limit, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallErrorSummaryInto(String agentRollupId, OverallQuery query,
            OverallErrorSummaryCollector collector) throws Exception {
        dataSource.query(new OverallErrorSummaryQuery(collector, query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionErrorSummariesInto(String agentRollupId, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit, TransactionErrorSummaryCollector collector)
            throws Exception {
        dataSource.query(new TransactionErrorSummaryQuery(query, sortOrder, limit,
                collector));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return dataSource.query(new OverviewAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return dataSource.query(new PercentileAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return dataSource.query(new ThroughputAggregateQuery(query));
    }

    @Override
    public @Nullable String readFullQueryText(String agentRollupId, String fullQueryTextSha1)
            throws Exception {
        return fullQueryTextDao.getFullText(fullQueryTextSha1);
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollupId, TransactionQuery query,
            QueryCollector collector) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery("queries_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Stored.QueriesByType> queries = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessages(cappedId.cappedId(), Stored.QueriesByType.parser());
            for (Stored.QueriesByType toBeMergedQueries : queries) {
                for (Stored.Query toBeMergedQuery : toBeMergedQueries.getQueryList()) {
                    collector.mergeQuery(toBeMergedQueries.getType(),
                            toBeMergedQuery.getTruncatedText(),
                            Strings.emptyToNull(toBeMergedQuery.getFullTextSha1()),
                            toBeMergedQuery.getTotalDurationNanos(),
                            toBeMergedQuery.getExecutionCount(), toBeMergedQuery.hasTotalRows(),
                            toBeMergedQuery.getTotalRows().getValue());
                }
            }
            collector.updateLastCaptureTime(captureTime);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeServiceCallsInto(String agentRollupId, TransactionQuery query,
            ServiceCallCollector collector) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds =
                dataSource.query(new CappedIdQuery("service_calls_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Aggregate.ServiceCallsByType> queries =
                    rollupCappedDatabases.get(query.rollupLevel()).readMessages(cappedId.cappedId(),
                            Aggregate.ServiceCallsByType.parser());
            if (queries != null) {
                collector.mergeServiceCalls(queries);
                collector.updateLastCaptureTime(captureTime);
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeMainThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        mergeProfilesInto(collector, query, "main_thread_profile_capped_id");
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        mergeProfilesInto(collector, query, "aux_thread_profile_capped_id");
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return !dataSource.query(new CappedIdQuery("main_thread_profile_capped_id", query))
                .isEmpty();
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return !dataSource.query(new CappedIdQuery("aux_thread_profile_capped_id", query))
                .isEmpty();
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return dataSource
                .query(new ShouldHaveSomethingQuery(query, "main_thread_profile_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return dataSource
                .query(new ShouldHaveSomethingQuery(query, "aux_thread_profile_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollupId, TransactionQuery query)
            throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "queries_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollupId, TransactionQuery query)
            throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "service_calls_capped_id"));
    }

    void deleteBefore(long captureTime, int rollupLevel) throws Exception {
        dataSource.deleteBefore("aggregate_tt_rollup_" + castUntainted(rollupLevel), captureTime);
        dataSource.deleteBefore("aggregate_tn_rollup_" + castUntainted(rollupLevel), captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        List<Long> rollupTimes = dataSource.query(new RollupTimeRowMapper(fromRollupLevel,
                fixedIntervalMillis, lastRollupTime, curentRollupTime));
        for (Long rollupTime : rollupTimes) {
            dataSource.query(new RollupOverallAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
            dataSource.query(new RollupTransactionAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
        }
    }

    private void mergeProfilesInto(ProfileCollector collector, TransactionQuery query,
            @Untainted String cappedIdColumnName) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery(cappedIdColumnName, query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            Profile profile = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessage(cappedId.cappedId(), Profile.parser());
            if (profile != null) {
                collector.mergeProfile(profile);
                collector.updateLastCaptureTime(captureTime);
            }
        }
    }

    private void merge(MutableAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex,
            int fromRollupLevel) throws Exception {
        int i = startColumnIndex;
        double totalDurationNanos = resultSet.getDouble(i++);
        long transactionCount = resultSet.getLong(i++);
        long errorCount = resultSet.getLong(i++);
        boolean asyncTransactions = resultSet.getBoolean(i++);
        Long queriesCappedId = RowMappers.getLong(resultSet, i++);
        Long serviceCallsCappedId = RowMappers.getLong(resultSet, i++);
        Long mainThreadProfileCappedId = RowMappers.getLong(resultSet, i++);
        Long auxThreadProfileCappedId = RowMappers.getLong(resultSet, i++);
        byte[] mainThreadRootTimers = resultSet.getBytes(i++);
        byte[] auxThreadRootTimers = resultSet.getBytes(i++);
        byte[] asyncTimers = resultSet.getBytes(i++);
        Double mainThreadTotalCpuNanos = RowMappers.getDouble(resultSet, i++);
        Double mainThreadTotalBlockedNanos = RowMappers.getDouble(resultSet, i++);
        Double mainThreadTotalWaitedNanos = RowMappers.getDouble(resultSet, i++);
        Double mainThreadTotalAllocatedBytes = RowMappers.getDouble(resultSet, i++);
        Double auxThreadTotalCpuNanos = RowMappers.getDouble(resultSet, i++);
        Double auxThreadTotalBlockedNanos = RowMappers.getDouble(resultSet, i++);
        Double auxThreadTotalWaitedNanos = RowMappers.getDouble(resultSet, i++);
        Double auxThreadTotalAllocatedBytes = RowMappers.getDouble(resultSet, i++);
        byte[] durationNanosHistogram = checkNotNull(resultSet.getBytes(i++));

        mergedAggregate.addTotalDurationNanos(totalDurationNanos);
        mergedAggregate.addTransactionCount(transactionCount);
        mergedAggregate.addErrorCount(errorCount);
        mergedAggregate.addAsyncTransactions(asyncTransactions);
        if (mainThreadRootTimers != null) {
            mergedAggregate.mergeMainThreadRootTimers(
                    readMessages(mainThreadRootTimers, Aggregate.Timer.parser()));
        }
        if (auxThreadRootTimers != null) {
            mergedAggregate.mergeAuxThreadRootTimers(
                    readMessages(auxThreadRootTimers, Aggregate.Timer.parser()));
        }
        if (asyncTimers != null) {
            mergedAggregate.mergeAsyncTimers(readMessages(asyncTimers, Aggregate.Timer.parser()));
        }
        mergedAggregate.addMainThreadTotalCpuNanos(mainThreadTotalCpuNanos);
        mergedAggregate.addMainThreadTotalBlockedNanos(mainThreadTotalBlockedNanos);
        mergedAggregate.addMainThreadTotalWaitedNanos(mainThreadTotalWaitedNanos);
        mergedAggregate.addMainThreadTotalAllocatedBytes(mainThreadTotalAllocatedBytes);
        mergedAggregate.addAuxThreadTotalCpuNanos(auxThreadTotalCpuNanos);
        mergedAggregate.addAuxThreadTotalBlockedNanos(auxThreadTotalBlockedNanos);
        mergedAggregate.addAuxThreadTotalWaitedNanos(auxThreadTotalWaitedNanos);
        mergedAggregate.addAuxThreadTotalAllocatedBytes(auxThreadTotalAllocatedBytes);
        mergedAggregate
                .mergeDurationNanosHistogram(Aggregate.Histogram.parseFrom(durationNanosHistogram));
        if (queriesCappedId != null) {
            List<Stored.QueriesByType> queries = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessages(queriesCappedId, Stored.QueriesByType.parser());
            if (queries != null) {
                for (Stored.QueriesByType queriesByType : queries) {
                    for (Stored.Query query : queriesByType.getQueryList()) {
                        mergedAggregate.mergeQuery(queriesByType.getType(),
                                query.getTruncatedText(),
                                Strings.emptyToNull(query.getFullTextSha1()),
                                query.getTotalDurationNanos(), query.getExecutionCount(),
                                query.hasTotalRows(), query.getTotalRows().getValue());
                    }
                }
            }
        }
        if (serviceCallsCappedId != null) {
            List<Aggregate.ServiceCallsByType> serviceCalls =
                    rollupCappedDatabases.get(fromRollupLevel).readMessages(serviceCallsCappedId,
                            Aggregate.ServiceCallsByType.parser());
            if (serviceCalls != null) {
                mergedAggregate.mergeServiceCalls(serviceCalls);
            }
        }
        if (mainThreadProfileCappedId != null) {
            Profile mainThreadProfile = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(mainThreadProfileCappedId, Profile.parser());
            if (mainThreadProfile != null) {
                mergedAggregate.mergeMainThreadProfile(mainThreadProfile);
            }
        }
        if (auxThreadProfileCappedId != null) {
            Profile auxThreadProfile = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(auxThreadProfileCappedId, Profile.parser());
            if (auxThreadProfile != null) {
                mergedAggregate.mergeAuxThreadProfile(auxThreadProfile);
            }
        }
    }

    private int getMaxAggregateQueriesPerType() {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(AGENT_ID);
        if (advancedConfig.hasMaxAggregateQueriesPerType()) {
            return advancedConfig.getMaxAggregateQueriesPerType().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_QUERIES_PER_TYPE;
        }
    }

    private int getMaxAggregateServiceCallsPerType() {
        AdvancedConfig advancedConfig = configRepository.getAdvancedConfig(AGENT_ID);
        if (advancedConfig.hasMaxAggregateServiceCallsPerType()) {
            return advancedConfig.getMaxAggregateServiceCallsPerType().getValue();
        } else {
            return ConfigDefaults.ADVANCED_MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE;
        }
    }

    private static @Untainted String getTableName(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "aggregate_tt_rollup_" + castUntainted(query.rollupLevel());
        } else {
            return "aggregate_tn_rollup_" + castUntainted(query.rollupLevel());
        }
    }

    private static @Untainted String getTransactionNameCriteria(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "";
        } else {
            return " and transaction_name = ?";
        }
    }

    private static int bindQuery(PreparedStatement preparedStatement, TransactionQuery query)
            throws SQLException {
        int i = 1;
        preparedStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            preparedStatement.setString(i++, transactionName);
        }
        preparedStatement.setLong(i++, query.from());
        preparedStatement.setLong(i++, query.to());
        return i;
    }

    private static <T extends /*@NonNull*/ Object> List<T> readMessages(byte[] bytes,
            Parser<T> parser) throws InvalidProtocolBufferException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        List<T> messages = Lists.newArrayList();
        T message;
        while ((message = parser.parseDelimitedFrom(bais)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private static class OverallSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallSummaryCollector collector;
        private final OverallQuery query;

        private OverallSummaryQuery(OverallSummaryCollector collector, OverallQuery query) {
            this.collector = collector;
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            return "select sum(total_duration_nanos), sum(transaction_count), max(capture_time)"
                    + " from aggregate_tt_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            int i = 1;
            double totalDurationNanos = resultSet.getDouble(i++);
            long transactionCount = resultSet.getLong(i++);
            long captureTime = resultSet.getLong(i++);
            collector.mergeSummary(totalDurationNanos, transactionCount, captureTime);
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }
    }

    private class TransactionSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallQuery query;
        private final SummarySortOrder sortOrder;
        private final int limit;

        private final TransactionSummaryCollector collector;

        private TransactionSummaryQuery(OverallQuery query, SummarySortOrder sortOrder, int limit,
                TransactionSummaryCollector collector) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.collector = collector;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(total_duration_nanos), sum(transaction_count),"
                    + " max(capture_time) from aggregate_tn_rollup_");
            sb.append(query.rollupLevel());
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(i++, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                int i = 1;
                String transactionName = checkNotNull(resultSet.getString(i++));
                double totalDurationNanos = resultSet.getDouble(i++);
                long transactionCount = resultSet.getLong(i++);
                long maxCaptureTime = resultSet.getLong(i++);
                collector.collect(transactionName, totalDurationNanos, transactionCount,
                        maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }

        private @Untainted String getSortClause(SummarySortOrder sortOrder) {
            switch (sortOrder) {
                case TOTAL_TIME:
                    return "sum(total_duration_nanos) desc";
                case AVERAGE_TIME:
                    return "sum(total_duration_nanos) / sum(transaction_count) desc";
                case THROUGHPUT:
                    return "sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }
    }

    private static class OverallErrorSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallErrorSummaryCollector collector;
        private final OverallQuery query;

        private OverallErrorSummaryQuery(OverallErrorSummaryCollector collector,
                OverallQuery query) {
            this.collector = collector;
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            return "select sum(error_count), sum(transaction_count), max(capture_time)"
                    + " from aggregate_tt_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            int i = 1;
            long errorCount = resultSet.getLong(i++);
            long transactionCount = resultSet.getLong(i++);
            long captureTime = resultSet.getLong(i++);
            collector.mergeErrorSummary(errorCount, transactionCount, captureTime);
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }
    }

    private class TransactionErrorSummaryQuery implements JdbcQuery</*@Nullable*/ Void> {

        private final OverallQuery query;
        private final ErrorSummarySortOrder sortOrder;
        private final int limit;

        private final TransactionErrorSummaryCollector collector;

        private TransactionErrorSummaryQuery(OverallQuery query, ErrorSummarySortOrder sortOrder,
                int limit, TransactionErrorSummaryCollector collector) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.collector = collector;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(error_count), sum(transaction_count),");
            sb.append(" max(capture_time) from aggregate_tn_rollup_");
            sb.append(castUntainted(query.rollupLevel()));
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name having sum(error_count) > 0 order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(i++, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                int i = 1;
                String transactionName = checkNotNull(resultSet.getString(i++));
                long errorCount = resultSet.getLong(i++);
                long transactionCount = resultSet.getLong(i++);
                long maxCaptureTime = resultSet.getLong(i++);
                collector.collect(transactionName, errorCount, transactionCount, maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }

        private @Untainted String getSortClause(ErrorSummarySortOrder sortOrder) {
            switch (sortOrder) {
                case ERROR_COUNT:
                    return "sum(error_count) desc";
                case ERROR_RATE:
                    return "sum(error_count) / sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }
    }

    private static class OverviewAggregateQuery implements JdbcRowQuery<OverviewAggregate> {

        private final TransactionQuery query;

        private OverviewAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_duration_nanos, transaction_count,"
                    + " async_transactions, main_thread_root_timers, aux_thread_root_timers,"
                    + " async_root_timers, main_thread_total_cpu_nanos,"
                    + " main_thread_total_blocked_nanos, main_thread_total_waited_nanos,"
                    + " main_thread_total_allocated_bytes, aux_thread_total_cpu_nanos,"
                    + " aux_thread_total_blocked_nanos, aux_thread_total_waited_nanos,"
                    + " aux_thread_total_allocated_bytes from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public OverviewAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalDurationNanos(resultSet.getDouble(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .asyncTransactions(resultSet.getBoolean(i++));
            byte[] mainThreadRootTimers = resultSet.getBytes(i++);
            if (mainThreadRootTimers != null) {
                builder.mainThreadRootTimers(
                        readMessages(mainThreadRootTimers, Aggregate.Timer.parser()));
            }
            byte[] auxThreadRootTimers = resultSet.getBytes(i++);
            if (auxThreadRootTimers != null) {
                builder.auxThreadRootTimers(
                        readMessages(auxThreadRootTimers, Aggregate.Timer.parser()));
            }
            byte[] asyncTimers = resultSet.getBytes(i++);
            if (asyncTimers != null) {
                builder.asyncTimers(readMessages(asyncTimers, Aggregate.Timer.parser()));
            }
            Aggregate.ThreadStats mainThreadStats =
                    ThreadStatsCreator.create(RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++));
            if (mainThreadStats != null) {
                builder.mainThreadStats(mainThreadStats);
            }
            Aggregate.ThreadStats auxThreadStats =
                    ThreadStatsCreator.create(RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++),
                            RowMappers.getDouble(resultSet, i++));
            if (auxThreadStats != null) {
                builder.auxThreadStats(auxThreadStats);
            }
            return builder.build();
        }
    }

    private static class PercentileAggregateQuery implements JdbcRowQuery<PercentileAggregate> {

        private final TransactionQuery query;

        private PercentileAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_duration_nanos, transaction_count,"
                    + " duration_nanos_histogram from " + tableName + " where transaction_type = ?"
                    + transactionNameCriteria + " and capture_time >= ? and capture_time <= ?"
                    + " order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public PercentileAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutablePercentileAggregate.Builder builder = ImmutablePercentileAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalDurationNanos(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++));
            byte[] durationNanosHistogram = checkNotNull(resultSet.getBytes(i++));
            builder.durationNanosHistogram(
                    Aggregate.Histogram.parser().parseFrom(durationNanosHistogram));
            return builder.build();
        }
    }

    private static class ThroughputAggregateQuery implements JdbcRowQuery<ThroughputAggregate> {

        private final TransactionQuery query;

        private ThroughputAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, transaction_count, error_count from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public ThroughputAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            return ImmutableThroughputAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .errorCount(resultSet.getLong(i++))
                    .build();
        }
    }

    private class RollupOverallAggregates implements JdbcQuery</*@Nullable*/ Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupOverallAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, total_duration_nanos, transaction_count, error_count,"
                    + " async_transactions, queries_capped_id, service_calls_capped_id,"
                    + " main_thread_profile_capped_id, aux_thread_profile_capped_id,"
                    + " main_thread_root_timers, aux_thread_root_timers, async_root_timers,"
                    + " main_thread_total_cpu_nanos, main_thread_total_blocked_nanos,"
                    + " main_thread_total_waited_nanos, main_thread_total_allocated_bytes,"
                    + " aux_thread_total_cpu_nanos, aux_thread_total_blocked_nanos,"
                    + " aux_thread_total_waited_nanos, aux_thread_total_allocated_bytes,"
                    + " duration_nanos_histogram from aggregate_tt_rollup_"
                    + castUntainted(fromRollupLevel) + " where capture_time > ?"
                    + " and capture_time <= ? order by transaction_type";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType();
            int maxAggregateServiceCallsPerType = getMaxAggregateServiceCallsPerType();
            CappedDatabase cappedDatabase = rollupCappedDatabases.get(toRollupLevel);
            MutableOverallAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                if (curr == null || !transactionType.equals(curr.transactionType())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(), null,
                                rollupCaptureTime, curr.aggregate(), toRollupLevel,
                                cappedDatabase, scratchBuffer));
                    }
                    curr = ImmutableMutableOverallAggregate.of(transactionType,
                            new MutableAggregate(maxAggregateQueriesPerType,
                                    maxAggregateServiceCallsPerType));
                }
                merge(curr.aggregate(), resultSet, 2, fromRollupLevel);
            }
            if (curr != null) {
                dataSource.update(new AggregateInsert(curr.transactionType(), null,
                        rollupCaptureTime, curr.aggregate(), toRollupLevel, cappedDatabase,
                        scratchBuffer));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }
    }

    private class RollupTransactionAggregates implements JdbcQuery</*@Nullable*/ Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;

        private RollupTransactionAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, transaction_name, total_duration_nanos,"
                    + " transaction_count, error_count, async_transactions, queries_capped_id,"
                    + " service_calls_capped_id, main_thread_profile_capped_id,"
                    + " aux_thread_profile_capped_id, main_thread_root_timers,"
                    + " aux_thread_root_timers, async_root_timers, main_thread_total_cpu_nanos,"
                    + " main_thread_total_blocked_nanos, main_thread_total_waited_nanos,"
                    + " main_thread_total_allocated_bytes, aux_thread_total_cpu_nanos,"
                    + " aux_thread_total_blocked_nanos, aux_thread_total_waited_nanos,"
                    + " aux_thread_total_allocated_bytes, duration_nanos_histogram"
                    + " from aggregate_tn_rollup_" + castUntainted(fromRollupLevel)
                    + " where capture_time > ? and capture_time <= ? order by transaction_type,"
                    + " transaction_name";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerType = getMaxAggregateQueriesPerType();
            int maxAggregateServiceCallsPerType = getMaxAggregateServiceCallsPerType();
            CappedDatabase cappedDatabase = rollupCappedDatabases.get(toRollupLevel);
            ScratchBuffer scratchBuffer = new ScratchBuffer();
            MutableTransactionAggregate curr = null;
            while (resultSet.next()) {
                int i = 1;
                String transactionType = checkNotNull(resultSet.getString(i++));
                String transactionName = checkNotNull(resultSet.getString(i++));
                if (curr == null || !transactionType.equals(curr.transactionType())
                        || !transactionName.equals(curr.transactionName())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(),
                                curr.transactionName(), rollupCaptureTime, curr.aggregate(),
                                toRollupLevel, cappedDatabase, scratchBuffer));
                    }
                    curr = ImmutableMutableTransactionAggregate.of(transactionType, transactionName,
                            new MutableAggregate(maxAggregateQueriesPerType,
                                    maxAggregateServiceCallsPerType));
                }
                merge(curr.aggregate(), resultSet, i++, fromRollupLevel);
            }
            if (curr != null) {
                dataSource.update(new AggregateInsert(curr.transactionType(),
                        curr.transactionName(), rollupCaptureTime, curr.aggregate(), toRollupLevel,
                        cappedDatabase, scratchBuffer));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosed() {
            return null;
        }
    }

    private class CappedIdQuery implements JdbcQuery<List<CappedId>> {

        private final @Untainted String cappedIdColumnName;
        private final TransactionQuery query;
        private final long smallestNonExpiredCappedId;

        private CappedIdQuery(@Untainted String cappedIdColumnName, TransactionQuery query) {
            this.cappedIdColumnName = cappedIdColumnName;
            this.query = query;
            smallestNonExpiredCappedId =
                    rollupCappedDatabases.get(query.rollupLevel()).getSmallestNonExpiredId();
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, " + cappedIdColumnName + " from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time > ? and capture_time <= ? and " + cappedIdColumnName
                    + " >= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = bindQuery(preparedStatement, query);
            preparedStatement.setLong(i++, smallestNonExpiredCappedId);
        }

        @Override
        public List<CappedId> processResultSet(ResultSet resultSet) throws Exception {
            List<CappedId> cappedIds = Lists.newArrayList();
            while (resultSet.next()) {
                cappedIds.add(ImmutableCappedId.of(resultSet.getLong(1), resultSet.getLong(2)));
            }
            return cappedIds;
        }

        @Override
        public List<CappedId> valueIfDataSourceClosed() {
            return ImmutableList.of();
        }
    }

    private static class ShouldHaveSomethingQuery implements JdbcQuery<Boolean> {

        private final TransactionQuery query;
        private final @Untainted String cappedIdColumnName;

        private ShouldHaveSomethingQuery(TransactionQuery query,
                @Untainted String cappedIdColumnName) {
            this.query = query;
            this.cappedIdColumnName = cappedIdColumnName;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select 1 from " + tableName + " where transaction_type = ?"
                    + transactionNameCriteria + " and capture_time > ? and capture_time <= ?"
                    + " and " + cappedIdColumnName + " is not null limit 1";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 1;
            preparedStatement.setString(i++, query.transactionType());
            String transactionName = query.transactionName();
            if (transactionName != null) {
                preparedStatement.setString(i++, transactionName);
            }
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
        }

        @Override
        public Boolean processResultSet(ResultSet resultSet) throws Exception {
            return resultSet.next();
        }

        @Override
        public Boolean valueIfDataSourceClosed() {
            return false;
        }
    }

    private static class RollupTimeRowMapper implements JdbcRowQuery<Long> {

        private final int rollupLevel;
        private final long fixedIntervalMillis;
        private final long lastRollupTime;
        private final long curentRollupTime;

        private RollupTimeRowMapper(int rollupLevel, long fixedIntervalMillis, long lastRollupTime,
                long curentRollupTime) {
            this.rollupLevel = rollupLevel;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.lastRollupTime = lastRollupTime;
            this.curentRollupTime = curentRollupTime;
        }

        @Override
        public @Untainted String getSql() {
            // need ".0" to force double result
            String captureTimeSql = castUntainted(
                    "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
            return "select distinct " + captureTimeSql + " from aggregate_tt_rollup_"
                    + castUntainted(rollupLevel) + " where capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, lastRollupTime);
            preparedStatement.setLong(2, curentRollupTime);
        }

        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CappedId {
        long captureTime();
        long cappedId();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableOverallAggregate {
        String transactionType();
        MutableAggregate aggregate();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableTransactionAggregate {
        String transactionType();
        String transactionName();
        MutableAggregate aggregate();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TruncatedQueryText {
        String truncatedText();
        @Nullable
        String fullTextSha1();
    }
}
