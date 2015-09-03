/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.server.simplerepo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.collector.spi.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.Clock;
import org.glowroot.live.ImmutableErrorPoint;
import org.glowroot.live.ImmutableOverallErrorSummary;
import org.glowroot.live.ImmutableOverallSummary;
import org.glowroot.live.ImmutableOverviewAggregate;
import org.glowroot.live.ImmutablePercentileAggregate;
import org.glowroot.live.ImmutableTransactionErrorSummary;
import org.glowroot.live.ImmutableTransactionSummary;
import org.glowroot.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.ConfigRepository.RollupConfig;
import org.glowroot.server.repo.MutableAggregate;
import org.glowroot.server.repo.ProfileCollector;
import org.glowroot.server.repo.Result;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.server.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.server.simplerepo.util.DataSource.RowMapper;
import org.glowroot.server.simplerepo.util.ImmutableColumn;
import org.glowroot.server.simplerepo.util.ImmutableIndex;
import org.glowroot.server.simplerepo.util.RowMappers;
import org.glowroot.server.simplerepo.util.Schemas.Column;
import org.glowroot.server.simplerepo.util.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.server.simplerepo.util.Checkers.castUntainted;

class AggregateDao implements AggregateRepository {

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_nanos", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", Types.BIGINT),
                    ImmutableColumn.of("queries_capped_id", Types.BIGINT), // protobuf
                    ImmutableColumn.of("profile_tree_capped_id", Types.BIGINT), // protobuf
                    ImmutableColumn.of("histogram", Types.BLOB), // protobuf
                    ImmutableColumn.of("root_timers", Types.BLOB)); // protobuf

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("transaction_name", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_nanos", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", Types.BIGINT),
                    ImmutableColumn.of("queries_capped_id", Types.BIGINT), // protobuf
                    ImmutableColumn.of("profile_tree_capped_id", Types.BIGINT), // protobuf
                    ImmutableColumn.of("histogram", Types.BLOB), // protobuf
                    ImmutableColumn.of("root_timers", Types.BLOB)); // protobuf

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns = ImmutableList.of(
            "capture_time", "transaction_type", "total_nanos", "transaction_count", "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name", "total_nanos",
                    "transaction_count", "error_count");

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final ConfigRepository configRepository;
    private final Clock clock;

    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            ConfigRepository configRepository, Clock clock) throws Exception {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.configRepository = configRepository;
        this.clock = clock;

        ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            String overallTableName = "overall_aggregate_rollup_" + castUntainted(i);
            dataSource.syncTable(overallTableName, overallAggregatePointColumns);
            dataSource.syncIndexes(overallTableName, ImmutableList.<Index>of(
                    ImmutableIndex.of(overallTableName + "_idx", overallAggregateIndexColumns)));
            String transactionTableName = "transaction_aggregate_rollup_" + castUntainted(i);
            dataSource.syncTable(transactionTableName, transactionAggregateColumns);
            dataSource.syncIndexes(transactionTableName, ImmutableList.<Index>of(ImmutableIndex
                    .of(transactionTableName + "_idx", transactionAggregateIndexColumns)));
        }

        // don't need last_rollup_times table like in GaugeValueDao since there is already index
        // on capture_time so these queries are relatively fast
        long[] lastRollupTimes = new long[rollupConfigs.size()];
        lastRollupTimes[0] = 0;
        for (int i = 1; i < lastRollupTimes.length; i++) {
            lastRollupTimes[i] = dataSource.queryForLong(
                    "select ifnull(max(capture_time), 0) from overall_aggregate_rollup_"
                            + castUntainted(i));
        }
        this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    void store(Map<String, Aggregate> overallAggregates,
            Map<String, Map<String, Aggregate>> transactionAggregates,
            long captureTime) throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        for (Entry<String, Aggregate> entry : overallAggregates.entrySet()) {
            storeOverallAggregate(0, entry.getKey(), entry.getValue());
        }
        for (Entry<String, Map<String, Aggregate>> outerEntry : transactionAggregates
                .entrySet()) {
            for (Entry<String, Aggregate> innerEntry : outerEntry.getValue().entrySet()) {
                storeTransactionAggregate(0, outerEntry.getKey(), innerEntry.getKey(),
                        innerEntry.getValue());
            }
        }
        synchronized (rollupLock) {
            ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                RollupConfig rollupConfig = rollupConfigs.get(i);
                long safeRollupTime = getSafeRollupTime(captureTime, rollupConfig.intervalMillis());
                if (safeRollupTime > lastRollupTimes.get(i)) {
                    rollup(lastRollupTimes.get(i), safeRollupTime, rollupConfig.intervalMillis(), i,
                            i - 1);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // captureTimeFrom is non-inclusive
    @Override
    public OverallSummary readOverallSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        long lastRollupTime = lastRollupTimes.get(rollupLevel);
        if (rollupLevel != 0 && captureTimeTo > lastRollupTime) {
            // need to aggregate some non-rolled up data
            OverallSummary overallSummary = readOverallSummaryInternal(transactionType,
                    captureTimeFrom, lastRollupTime, rollupLevel);
            OverallSummary sinceLastRollupSummary =
                    readOverallSummaryInternal(transactionType, lastRollupTime, captureTimeTo, 0);
            return combineOverallSummaries(overallSummary, sinceLastRollupSummary);
        }
        return readOverallSummaryInternal(transactionType, captureTimeFrom, captureTimeTo,
                rollupLevel);
    }

    // query.from() is non-inclusive
    @Override
    public Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception {
        int rollupLevel = getRollupLevelForView(query.from(), query.to());
        ImmutableList<TransactionSummary> summaries;
        long lastRollupTime = lastRollupTimes.get(rollupLevel);
        if (rollupLevel != 0 && query.to() > lastRollupTime) {
            // need to aggregate some non-rolled up data
            summaries = readTransactionSummariesInternalSplit(query, rollupLevel, lastRollupTime);
        } else {
            summaries = readTransactionSummariesInternal(query, rollupLevel);
        }
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(summaries, query.limit());
    }

    // captureTimeFrom is non-inclusive
    @Override
    public OverallErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        OverallErrorSummary result = dataSource.query("select sum(error_count),"
                + " sum(transaction_count) from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", new OverallErrorSummaryResultSetExtractor(),
                transactionType, captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return ImmutableOverallErrorSummary.builder().build();
        } else {
            return result;
        }
    }

    // captureTimeFrom is non-inclusive
    @Override
    public Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query,
            int rollupLevel) throws Exception {
        ImmutableList<TransactionErrorSummary> summary = dataSource.query("select transaction_name,"
                + " sum(error_count), sum(transaction_count) from transaction_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? group by transaction_type, transaction_name"
                + " having sum(error_count) > 0 order by " + getSortClause(query.sortOrder())
                + ", transaction_type, transaction_name limit ?", new ErrorSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(summary, query.limit());
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public ImmutableList<OverviewAggregate> readOverallOverviewAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query("select capture_time, total_nanos, transaction_count,"
                + " total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                + " total_allocated_bytes, root_timers from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time >= ?"
                + " and capture_time <= ? order by capture_time", new OverviewAggregateRowMapper(),
                transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public ImmutableList<PercentileAggregate> readOverallPercentileAggregates(
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                        + " order by capture_time",
                new PercentileAggregateRowMapper(), transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public ImmutableList<OverviewAggregate> readTransactionOverviewAggregates(
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, total_cpu_nanos,"
                        + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                        + " root_timers from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where transaction_type = ?"
                        + " and transaction_name = ? and capture_time >= ? and capture_time <= ?"
                        + " order by capture_time",
                new OverviewAggregateRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public ImmutableList<PercentileAggregate> readTransactionPercentileAggregates(
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and transaction_name = ?"
                        + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new PercentileAggregateRowMapper(), transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInOverallQueries(QueryCollector mergedQueries, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        dataSource.query("select capture_time, queries_capped_id from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? and queries_capped_id >= ?  order by capture_time",
                new MergedQueriesResultSetExtractor(mergedQueries, rollupLevel), transactionType,
                captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInTransactionQueries(QueryCollector mergedQueries, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        dataSource.query(
                "select capture_time, queries_capped_id from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and queries_capped_id >= ? order by capture_time",
                new MergedQueriesResultSetExtractor(mergedQueries, rollupLevel), transactionType,
                transactionName, captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInOverallProfile(ProfileCollector mergedProfile, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        dataSource
                .query("select capture_time, profile_tree_capped_id from overall_aggregate_rollup_"
                        + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? and profile_tree_capped_id >= ?",
                        new ProfileMergingResultSetExtractor(mergedProfile, rollupLevel),
                        transactionType,
                        captureTimeFrom, captureTimeTo,
                        rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInTransactionProfile(ProfileCollector mergedProfile, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        dataSource.query(
                "select capture_time, profile_tree_capped_id from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and profile_tree_capped_id >= ?",
                new ProfileMergingResultSetExtractor(mergedProfile, rollupLevel), transactionType,
                transactionName, captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    @Override
    public ImmutableList<ErrorPoint> readOverallErrorPoints(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, sum(error_count), sum(transaction_count)"
                        + " from overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                        + " group by capture_time having sum(error_count) > 0"
                        + " order by capture_time",
                new ErrorPointRowMapper(), transactionType, captureTimeFrom, captureTimeTo);
    }

    @Override
    public ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        return dataSource.query(
                "select capture_time, error_count, transaction_count from"
                        + " transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and transaction_name = ?"
                        + " and capture_time >= ? and capture_time <= ? and error_count > 0"
                        + " order by capture_time",
                new ErrorPointRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveOverallQueries(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return shouldHaveOverallSomething("queries_capped_id", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveTransactionQueries(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveTransactionSomething("queries_capped_id", transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveOverallProfile(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return shouldHaveOverallSomething("profile_tree_capped_id", transactionType,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveTransactionProfile(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveTransactionSomething("profile_tree_capped_id", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    @Override
    public long getDataPointIntervalMillis(long captureTimeFrom, long captureTimeTo) {
        long millis = captureTimeTo - captureTimeFrom;
        long timeAgoMillis = clock.currentTimeMillis() - captureTimeFrom;
        ImmutableList<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            RollupConfig currRollupConfig = rollupConfigs.get(i);
            RollupConfig nextRollupConfig = rollupConfigs.get(i + 1);
            if (millis < nextRollupConfig.viewThresholdMillis()
                    && HOURS.toMillis(rollupExpirationHours.get(i)) > timeAgoMillis) {
                return currRollupConfig.intervalMillis();
            }
        }
        return rollupConfigs.get(rollupConfigs.size() - 1).intervalMillis();
    }

    @Override
    public int getRollupLevelForView(long captureTimeFrom, long captureTimeTo) {
        long millis = captureTimeTo - captureTimeFrom;
        long timeAgoMillis = clock.currentTimeMillis() - captureTimeFrom;
        ImmutableList<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            RollupConfig nextRollupConfig = rollupConfigs.get(i + 1);
            if (millis < nextRollupConfig.viewThresholdMillis()
                    && HOURS.toMillis(rollupExpirationHours.get(i)) > timeAgoMillis) {
                return i;
            }
        }
        return rollupConfigs.size() - 1;
    }

    @Override
    public void deleteAll() throws SQLException {
        for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
            dataSource.execute("truncate table overall_aggregate_rollup_" + castUntainted(i));
            dataSource.execute("truncate table transaction_aggregate_rollup_" + castUntainted(i));
        }
    }

    void deleteBefore(long captureTime, int rollupLevel) throws SQLException {
        dataSource.deleteBefore("overall_aggregate_rollup_" + castUntainted(rollupLevel),
                captureTime);
        dataSource.deleteBefore("transaction_aggregate_rollup_" + castUntainted(rollupLevel),
                captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        List<Long> rollupTimes = dataSource.query(
                "select distinct " + captureTimeSql + " from overall_aggregate_rollup_"
                        + castUntainted(fromRollupLevel)
                        + " where capture_time > ? and capture_time <= ?",
                new LongRowMapper(), lastRollupTime, curentRollupTime);
        for (Long rollupTime : rollupTimes) {
            rollupOneInterval(rollupTime, fixedIntervalMillis, toRollupLevel, fromRollupLevel);
        }
    }

    private void rollupOneInterval(long rollupTime, long fixedIntervalMillis, int toRollupLevel,
            int fromRollupLevel) throws Exception {
        Map<String, MutableAggregate> overallAggregates = dataSource.query(
                "select transaction_type, total_nanos, transaction_count, error_count,"
                        + " total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                        + " total_allocated_bytes, queries_capped_id, profile_tree_capped_id,"
                        + " histogram, root_timers from overall_aggregate_rollup_"
                        + castUntainted(fromRollupLevel)
                        + " where capture_time > ? and capture_time <= ?",
                new OverallRollupResultSetExtractor(rollupTime, fromRollupLevel),
                rollupTime - fixedIntervalMillis, rollupTime);
        if (overallAggregates == null) {
            // data source is closing
            return;
        }
        Map<String, Map<String, MutableAggregate>> transactionAggregates = dataSource.query(
                "select transaction_type, transaction_name, total_nanos, transaction_count,"
                        + " error_count, total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                        + " total_allocated_bytes, queries_capped_id, profile_tree_capped_id,"
                        + " histogram, root_timers from transaction_aggregate_rollup_"
                        + castUntainted(fromRollupLevel)
                        + " where capture_time > ? and capture_time <= ?",
                new TransactionRollupResultSetExtractor(rollupTime, fromRollupLevel),
                rollupTime - fixedIntervalMillis, rollupTime);
        if (transactionAggregates == null) {
            // data source is closing
            return;
        }
        storeMergedAggregatesAtRollupLevel(overallAggregates, transactionAggregates, toRollupLevel);
    }

    private void storeMergedAggregatesAtRollupLevel(Map<String, MutableAggregate> overallAggregates,
            Map<String, Map<String, MutableAggregate>> transactionAggregates, int rollupLevel)
                    throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        for (Entry<String, MutableAggregate> entry : overallAggregates.entrySet()) {
            storeOverallAggregate(rollupLevel, entry.getKey(),
                    entry.getValue().toAggregate(scratchBuffer));
        }
        for (Entry<String, Map<String, MutableAggregate>> outerEntry : transactionAggregates
                .entrySet()) {
            for (Entry<String, MutableAggregate> innerEntry : outerEntry.getValue().entrySet()) {
                storeTransactionAggregate(rollupLevel, outerEntry.getKey(), innerEntry.getKey(),
                        innerEntry.getValue().toAggregate(scratchBuffer));
            }
        }
    }

    private void storeTransactionAggregate(int rollupLevel, String transactionType,
            String transactionName, Aggregate aggregate) throws Exception {
        dataSource.update(
                "insert into transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (transaction_type, transaction_name, capture_time, total_nanos,"
                        + " transaction_count, error_count, total_cpu_nanos, total_blocked_nanos,"
                        + " total_waited_nanos, total_allocated_bytes, queries_capped_id,"
                        + " profile_tree_capped_id, histogram, root_timers) values"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionAggregateBinder(transactionType, transactionName, aggregate,
                        rollupLevel));
    }

    private void storeOverallAggregate(int rollupLevel, String transactionType, Aggregate aggregate)
            throws Exception {
        dataSource.update(
                "insert into overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (transaction_type, capture_time, total_nanos, transaction_count,"
                        + " error_count, total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                        + " total_allocated_bytes, queries_capped_id, profile_tree_capped_id,"
                        + " histogram, root_timers) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallAggregateBinder(transactionType, aggregate, rollupLevel));
    }

    // captureTimeFrom is non-inclusive
    private OverallSummary readOverallSummaryInternal(String transactionType, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        OverallSummary summary = dataSource.query("select sum(total_nanos),"
                + " sum(transaction_count) from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", new OverallSummaryResultSetExtractor(), transactionType,
                captureTimeFrom, captureTimeTo);
        if (summary == null) {
            // this can happen if datasource is in the middle of closing
            return ImmutableOverallSummary.builder().build();
        } else {
            return summary;
        }
    }

    private OverallSummary combineOverallSummaries(OverallSummary overallSummary1,
            OverallSummary overallSummary2) {
        return ImmutableOverallSummary.builder()
                .transactionCount(
                        overallSummary1.transactionCount() + overallSummary2.transactionCount())
                .totalNanos(overallSummary1.totalNanos() + overallSummary2.totalNanos())
                .build();
    }

    private ImmutableList<TransactionSummary> readTransactionSummariesInternal(
            TransactionSummaryQuery query, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query(
                "select transaction_name, sum(total_nanos), sum(transaction_count)"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                        + " group by transaction_name order by " + getSortClause(query.sortOrder())
                        + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.transactionType(), query.from(),
                query.to(), query.limit() + 1);
    }

    private ImmutableList<TransactionSummary> readTransactionSummariesInternalSplit(
            TransactionSummaryQuery query, int rollupLevel, long lastRollupTime) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query(
                "select transaction_name, sum(total_nanos), sum(transaction_count)"
                        + " from (select transaction_name, total_nanos, transaction_count"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                        + " union all select transaction_name, total_nanos, transaction_count"
                        + " from transaction_aggregate_rollup_0 where transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ?) group by transaction_name"
                        + " order by " + getSortClause(query.sortOrder())
                        + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.transactionType(), query.from(),
                lastRollupTime, query.transactionType(), lastRollupTime, query.to(),
                query.limit() + 1);
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveOverallSomething(@Untainted String cappedIdColumnName,
            String transactionType, long captureTimeFrom, long captureTimeTo) throws Exception {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists("select 1 from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? and " + cappedIdColumnName + " is not null limit 1",
                transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveTransactionSomething(@Untainted String cappedIdColumnName,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists(
                "select 1 from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and transaction_name = ?"
                        + " and capture_time > ? and capture_time <= ? and " + cappedIdColumnName
                        + " is not null limit 1",
                transactionType, transactionName, captureTimeFrom, captureTimeTo);
    }

    private void merge(MutableAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex,
            int fromRollupLevel) throws Exception {
        int i = startColumnIndex;
        double totalNanos = resultSet.getDouble(i++);
        long transactionCount = resultSet.getLong(i++);
        long errorCount = resultSet.getLong(i++);
        double totalCpuNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalBlockedNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalWaitedNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalAllocatedBytes = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        Long queriesCappedId = RowMappers.getLong(resultSet, i++);
        Long profileCappedId = RowMappers.getLong(resultSet, i++);
        byte[] histogram = checkNotNull(resultSet.getBytes(i++));
        byte[] rootTimers = checkNotNull(resultSet.getBytes(i++));

        mergedAggregate.addTotalNanos(totalNanos);
        mergedAggregate.addTransactionCount(transactionCount);
        mergedAggregate.addErrorCount(errorCount);
        mergedAggregate.addTotalCpuNanos(totalCpuNanos);
        mergedAggregate.addTotalBlockedNanos(totalBlockedNanos);
        mergedAggregate.addTotalWaitedNanos(totalWaitedNanos);
        mergedAggregate.addTotalAllocatedBytes(totalAllocatedBytes);
        mergedAggregate.mergeHistogram(Aggregate.Histogram.parseFrom(histogram));
        mergedAggregate.mergeRootTimers(readMessages(rootTimers, Aggregate.Timer.parser()));
        if (queriesCappedId != null) {
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessages(queriesCappedId, Aggregate.QueriesByType.parser());
            if (queries != null) {
                mergedAggregate.mergeQueries(queries);
            }
        }
        if (profileCappedId != null) {
            ProfileTree profileTree = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(profileCappedId, ProfileTree.parser());
            if (profileTree != null) {
                mergedAggregate.mergeProfile(profileTree);
            }
        }
    }

    static long getSafeRollupTime(long captureTime, long intervalMillis) {
        return (long) Math.floor(captureTime / (double) intervalMillis) * intervalMillis;
    }

    private static @Untainted String getSortClause(TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return "sum(total_nanos) desc";
            case AVERAGE_TIME:
                return "sum(total_nanos) / sum(transaction_count) desc";
            case THROUGHPUT:
                return "sum(transaction_count) desc";
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    private static @Untainted String getSortClause(
            AggregateRepository.ErrorSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case ERROR_COUNT:
                return "sum(error_count) desc";
            case ERROR_RATE:
                return "sum(error_count) / sum(transaction_count) desc";
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    private static byte[] writeMessages(List<? extends AbstractMessageLite> messages)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (AbstractMessageLite message : messages) {
            message.writeDelimitedTo(baos);
        }
        return baos.toByteArray();
    }

    private static <T extends /*@NonNull*/Object> List<T> readMessages(byte[] bytes,
            Parser<T> parser) throws InvalidProtocolBufferException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        List<T> messages = Lists.newArrayList();
        T message;
        while ((message = parser.parseDelimitedFrom(bais)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private class AggregateBinder {

        private final Aggregate aggregate;
        private final @Nullable Long queriesCappedId;
        private final @Nullable Long profileCappedId;
        private final byte[] histogramBytes;
        private final byte[] rootTimers;

        private AggregateBinder(Aggregate aggregate, int rollupLevel) throws IOException {
            this.aggregate = aggregate;

            List<QueriesByType> queries = aggregate.getQueriesByTypeList();
            if (queries.isEmpty()) {
                queriesCappedId = null;
            } else {
                queriesCappedId = rollupCappedDatabases.get(rollupLevel).writeMessages(queries,
                        RollupCappedDatabaseStats.AGGREGATE_QUERIES);
            }
            ProfileTree profileTree = aggregate.getProfileTree();
            if (profileTree.getNodeCount() == 0) {
                profileCappedId = null;
            } else {
                profileCappedId = rollupCappedDatabases.get(rollupLevel).writeMessage(profileTree,
                        RollupCappedDatabaseStats.AGGREGATE_PROFILES);
            }
            histogramBytes = aggregate.getTotalNanosHistogram().toByteArray();
            rootTimers = writeMessages(aggregate.getRootTimerList());
        }

        // minimal work inside this method as it is called with active connection
        void bindCommon(PreparedStatement preparedStatement, int startIndex) throws Exception {
            int i = startIndex;
            preparedStatement.setLong(i++, aggregate.getCaptureTime());
            preparedStatement.setDouble(i++, aggregate.getTotalNanos());
            preparedStatement.setLong(i++, aggregate.getTransactionCount());
            preparedStatement.setLong(i++, aggregate.getErrorCount());
            RowMappers.setNotAvailableAwareDouble(preparedStatement, i++,
                    aggregate.getTotalCpuNanos());
            RowMappers.setNotAvailableAwareDouble(preparedStatement, i++,
                    aggregate.getTotalBlockedNanos());
            RowMappers.setNotAvailableAwareDouble(preparedStatement, i++,
                    aggregate.getTotalWaitedNanos());
            RowMappers.setNotAvailableAwareDouble(preparedStatement, i++,
                    aggregate.getTotalAllocatedBytes());
            RowMappers.setLong(preparedStatement, i++, queriesCappedId);
            RowMappers.setLong(preparedStatement, i++, profileCappedId);

            preparedStatement.setBytes(i++, histogramBytes);
            preparedStatement.setBytes(i++, rootTimers);
        }
    }

    private class OverallAggregateBinder extends AggregateBinder
            implements PreparedStatementBinder {

        private final String transactionType;

        private OverallAggregateBinder(String transactionType, Aggregate aggregate, int rollupLevel)
                throws IOException {
            super(aggregate, rollupLevel);
            this.transactionType = transactionType;
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, transactionType);
            bindCommon(preparedStatement, 2);
            preparedStatement.addBatch();
        }
    }

    private class TransactionAggregateBinder extends AggregateBinder
            implements PreparedStatementBinder {

        private final String transactionType;
        private final String transactionName;

        private TransactionAggregateBinder(String transactionType, String transactionName,
                Aggregate aggregate, int rollupLevel) throws IOException {
            super(aggregate, rollupLevel);
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, transactionType);
            preparedStatement.setString(2, transactionName);
            bindCommon(preparedStatement, 3);
            preparedStatement.addBatch();
        }
    }

    private static class OverallSummaryResultSetExtractor
            implements ResultSetExtractor<OverallSummary> {
        @Override
        public OverallSummary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableOverallSummary.builder()
                    .totalNanos(resultSet.getDouble(1))
                    .transactionCount(resultSet.getLong(2))
                    .build();
        }
    }

    private static class TransactionSummaryRowMapper implements RowMapper<TransactionSummary> {
        @Override
        public TransactionSummary mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            if (transactionName == null) {
                // transaction_name should never be null
                throw new SQLException("Found null transaction_name in transaction_aggregate");
            }
            return ImmutableTransactionSummary.builder()
                    .transactionName(transactionName)
                    .totalNanos(resultSet.getDouble(2))
                    .transactionCount(resultSet.getLong(3))
                    .build();
        }
    }

    private static class OverallErrorSummaryResultSetExtractor
            implements ResultSetExtractor<OverallErrorSummary> {
        @Override
        public OverallErrorSummary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableOverallErrorSummary.builder()
                    .errorCount(resultSet.getLong(1))
                    .transactionCount(resultSet.getLong(2))
                    .build();
        }
    }

    private static class ErrorSummaryRowMapper implements RowMapper<TransactionErrorSummary> {
        @Override
        public TransactionErrorSummary mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            if (transactionName == null) {
                // transaction_name should never be null
                throw new SQLException("Found null transaction_name in transaction_aggregate");
            }
            return ImmutableTransactionErrorSummary.builder()
                    .transactionName(transactionName)
                    .errorCount(resultSet.getLong(2))
                    .transactionCount(resultSet.getLong(3))
                    .build();
        }
    }

    private static class OverviewAggregateRowMapper implements RowMapper<OverviewAggregate> {

        @Override
        public OverviewAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalNanos(resultSet.getDouble(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .totalCpuNanos(resultSet.getDouble(i++))
                    .totalBlockedNanos(resultSet.getDouble(i++))
                    .totalWaitedNanos(resultSet.getDouble(i++))
                    .totalAllocatedBytes(resultSet.getDouble(i++));
            byte[] rootTimers = checkNotNull(resultSet.getBytes(i++));
            builder.rootTimers(readMessages(rootTimers, Aggregate.Timer.parser()));
            return builder.build();
        }
    }

    private static class PercentileAggregateRowMapper implements RowMapper<PercentileAggregate> {

        @Override
        public PercentileAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutablePercentileAggregate.Builder builder = ImmutablePercentileAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalNanos(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++));
            byte[] histogram = checkNotNull(resultSet.getBytes(i++));
            builder.histogram(Aggregate.Histogram.parser().parseFrom(histogram));
            return builder.build();
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<ErrorPoint> {
        @Override
        public ErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            long transactionCount = resultSet.getLong(3);
            return ImmutableErrorPoint.of(captureTime, errorCount, transactionCount);
        }
    }

    private class ProfileMergingResultSetExtractor
            implements ResultSetExtractor</*@Nullable*/Void> {

        private final ProfileCollector mergedProfile;
        private final int rollupLevel;

        public ProfileMergingResultSetExtractor(ProfileCollector mergedProfile, int rollupLevel) {
            this.mergedProfile = mergedProfile;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public @Nullable Void extractData(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return null;
            }
            long captureTime = Long.MIN_VALUE;
            while (resultSet.next()) {
                captureTime = Math.max(captureTime, resultSet.getLong(1));
                ProfileTree profileTree = rollupCappedDatabases.get(rollupLevel)
                        .readMessage(resultSet.getLong(2), ProfileTree.parser());
                if (profileTree != null) {
                    mergedProfile.mergeProfileTree(profileTree);
                    mergedProfile.updateLastCaptureTime(captureTime);
                }
            }
            return null;
        }
    }

    private class MergedQueriesResultSetExtractor implements ResultSetExtractor</*@Nullable*/Void> {

        private final QueryCollector mergedQueries;
        private final int rollupLevel;

        public MergedQueriesResultSetExtractor(QueryCollector mergedQueries, int rollupLevel) {
            this.mergedQueries = mergedQueries;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public @Nullable Void extractData(ResultSet resultSet) throws Exception {
            long captureTime = Long.MIN_VALUE;
            while (resultSet.next()) {
                captureTime = Math.max(captureTime, resultSet.getLong(1));
                List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(rollupLevel)
                        .readMessages(resultSet.getLong(2), Aggregate.QueriesByType.parser());
                if (queries != null) {
                    mergedQueries.mergeQueries(queries);
                    mergedQueries.updateLastCaptureTime(captureTime);
                }
            }
            return null;
        }
    }

    private static class LongRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    private class OverallRollupResultSetExtractor
            implements ResultSetExtractor<Map<String, MutableAggregate>> {

        private final long rollupCaptureTime;
        private final int fromRollupLevel;

        private OverallRollupResultSetExtractor(long rollupCaptureTime, int fromRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
        }

        @Override
        public Map<String, MutableAggregate> extractData(ResultSet resultSet) throws Exception {
            Map<String, MutableAggregate> mergedAggregates = Maps.newHashMap();
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                MutableAggregate mergedAggregate = mergedAggregates.get(transactionType);
                if (mergedAggregate == null) {
                    mergedAggregate = new MutableAggregate(rollupCaptureTime,
                            configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
                    mergedAggregates.put(transactionType, mergedAggregate);
                }
                merge(mergedAggregate, resultSet, 2, fromRollupLevel);
            }
            return mergedAggregates;
        }
    }

    private class TransactionRollupResultSetExtractor
            implements ResultSetExtractor<Map<String, Map<String, MutableAggregate>>> {

        private final long rollupCaptureTime;
        private final int fromRollupLevel;

        private TransactionRollupResultSetExtractor(long rollupCaptureTime, int fromRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
        }

        @Override
        public Map<String, Map<String, MutableAggregate>> extractData(ResultSet resultSet)
                throws Exception {
            Map<String, Map<String, MutableAggregate>> mergedAggregates = Maps.newHashMap();
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                String transactionName = checkNotNull(resultSet.getString(2));
                Map<String, MutableAggregate> mergedAggregateMap =
                        mergedAggregates.get(transactionType);
                if (mergedAggregateMap == null) {
                    mergedAggregateMap = Maps.newHashMap();
                    mergedAggregates.put(transactionType, mergedAggregateMap);
                }
                MutableAggregate mergedAggregate = mergedAggregateMap.get(transactionName);
                if (mergedAggregate == null) {
                    mergedAggregate = new MutableAggregate(rollupCaptureTime,
                            configRepository.getAdvancedConfig().maxAggregateQueriesPerQueryType());
                    mergedAggregateMap.put(transactionName, mergedAggregate);
                }
                merge(mergedAggregate, resultSet, 3, fromRollupLevel);
            }
            return mergedAggregates;
        }
    }
}
