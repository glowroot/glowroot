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
package org.glowroot.storage.simplerepo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableErrorPoint;
import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableTransactionErrorSummary;
import org.glowroot.common.live.ImmutableTransactionSummary;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.simplerepo.util.CappedDatabase;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.DataSource.RowMapper;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.RowMappers;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("server_id", ColumnType.BIGINT),
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", ColumnType.BIGINT),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("profile_tree_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("histogram", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("root_timers", ColumnType.VARBINARY)); // protobuf

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("server_id", ColumnType.BIGINT),
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", ColumnType.BIGINT),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("profile_tree_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("histogram", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("root_timers", ColumnType.VARBINARY)); // protobuf

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("server_id", "capture_time", "transaction_type", "total_nanos",
                    "transaction_count", "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("server_id", "capture_time", "transaction_type", "transaction_name",
                    "total_nanos", "transaction_count", "error_count");

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final ConfigRepository configRepository;
    private final Clock clock;

    // FIXME these need to be per server_id
    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            ConfigRepository configRepository, Clock clock) throws Exception {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.configRepository = configRepository;
        this.clock = clock;

        ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        Schema schema = dataSource.getSchema();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            String overallTableName = "overall_aggregate_rollup_" + castUntainted(i);
            schema.syncTable(overallTableName, overallAggregatePointColumns);
            schema.syncIndexes(overallTableName, ImmutableList.<Index>of(
                    ImmutableIndex.of(overallTableName + "_idx", overallAggregateIndexColumns)));
            String transactionTableName = "transaction_aggregate_rollup_" + castUntainted(i);
            schema.syncTable(transactionTableName, transactionAggregateColumns);
            schema.syncIndexes(transactionTableName, ImmutableList.<Index>of(ImmutableIndex
                    .of(transactionTableName + "_idx", transactionAggregateIndexColumns)));
        }

        // don't need last_rollup_times table like in GaugeValueDao since there is already index
        // on capture_time so these queries are relatively fast
        long[] lastRollupTimes = new long[rollupConfigs.size()];
        lastRollupTimes[0] = 0;
        for (int i = 1; i < lastRollupTimes.length; i++) {
            lastRollupTimes[i] = dataSource.queryForLong(
                    "select coalesce(max(capture_time), 0) from overall_aggregate_rollup_"
                            + castUntainted(i));
        }
        this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public void store(long serverId, long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        for (OverallAggregate overallAggregate : overallAggregates) {
            storeOverallAggregate(serverId, captureTime, overallAggregate.getTransactionType(),
                    overallAggregate.getAggregate(), 0);
        }
        for (TransactionAggregate transactionAggregate : transactionAggregates) {
            storeTransactionAggregate(serverId, captureTime,
                    transactionAggregate.getTransactionType(),
                    transactionAggregate.getTransactionName(), transactionAggregate.getAggregate(),
                    0);
        }
        synchronized (rollupLock) {
            ImmutableList<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                RollupConfig rollupConfig = rollupConfigs.get(i);
                long safeRollupTime = getSafeRollupTime(captureTime, rollupConfig.intervalMillis());
                if (safeRollupTime > lastRollupTimes.get(i)) {
                    rollup(serverId, lastRollupTimes.get(i), safeRollupTime,
                            rollupConfig.intervalMillis(), i, i - 1);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // captureTimeFrom is non-inclusive
    @Override
    public OverallSummary readOverallSummary(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        int rollupLevel = getRollupLevelForView(serverId, captureTimeFrom, captureTimeTo);
        long lastRollupTime = lastRollupTimes.get(rollupLevel);
        if (rollupLevel != 0 && captureTimeTo > lastRollupTime) {
            // need to aggregate some non-rolled up data
            OverallSummary overallSummary = readOverallSummaryInternal(serverId, transactionType,
                    captureTimeFrom, lastRollupTime, rollupLevel);
            OverallSummary sinceLastRollupSummary = readOverallSummaryInternal(serverId,
                    transactionType, lastRollupTime, captureTimeTo, 0);
            return combineOverallSummaries(overallSummary, sinceLastRollupSummary);
        }
        return readOverallSummaryInternal(serverId, transactionType, captureTimeFrom, captureTimeTo,
                rollupLevel);
    }

    // query.from() is non-inclusive
    @Override
    public Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception {
        int rollupLevel = getRollupLevelForView(query.serverId(), query.from(), query.to());
        List<TransactionSummary> summaries;
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
    public OverallErrorSummary readOverallErrorSummary(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        OverallErrorSummary result = dataSource.query("select sum(error_count),"
                + " sum(transaction_count) from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where server_id = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?",
                new OverallErrorSummaryResultSetExtractor(), serverId, transactionType,
                captureTimeFrom, captureTimeTo);
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
        List<TransactionErrorSummary> summary = dataSource.query("select transaction_name,"
                + " sum(error_count), sum(transaction_count) from transaction_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where server_id = ? and transaction_type = ?"
                + "and capture_time > ? and capture_time <= ? group by transaction_type,"
                + " transaction_name having sum(error_count) > 0 order by "
                + getSortClause(query.sortOrder()) + ", transaction_type, transaction_name limit ?",
                new ErrorSummaryRowMapper(), query.serverId(), query.transactionType(),
                query.from(), query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(summary, query.limit());
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverallOverviewAggregates(long serverId,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        return dataSource.query("select capture_time, total_nanos, transaction_count,"
                + " total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                + " total_allocated_bytes, root_timers from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where server_id = ? and transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new OverviewAggregateRowMapper(), serverId, transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public List<PercentileAggregate> readOverallPercentileAggregates(long serverId,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time >= ?"
                        + " and capture_time <= ? order by capture_time",
                new PercentileAggregateRowMapper(), serverId, transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public List<OverviewAggregate> readTransactionOverviewAggregates(long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, total_cpu_nanos,"
                        + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                        + " root_timers from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where server_id = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time >= ?"
                        + " and capture_time <= ? order by capture_time",
                new OverviewAggregateRowMapper(), serverId, transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public List<PercentileAggregate> readTransactionPercentileAggregates(long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, total_nanos, transaction_count, histogram"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and transaction_name = ?"
                        + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new PercentileAggregateRowMapper(), serverId, transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInOverallProfiles(ProfileCollector mergedProfile, long serverId,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(
                "select capture_time, profile_tree_capped_id from overall_aggregate_rollup_"
                        + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? and profile_tree_capped_id >= ?",
                new CappedIdRowMapper(), serverId, transactionType, captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
        mergeInProfiles(mergedProfile, rollupLevel, cappedIds);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInTransactionProfiles(ProfileCollector mergedProfile, long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(
                "select capture_time, profile_tree_capped_id from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where server_id = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                        + " and capture_time <= ? and profile_tree_capped_id >= ?",
                new CappedIdRowMapper(), serverId, transactionType, transactionName,
                captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
        mergeInProfiles(mergedProfile, rollupLevel, cappedIds);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInOverallQueries(QueryCollector mergedQueries, long serverId,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(
                "select capture_time, queries_capped_id from overall_aggregate_rollup_"
                        + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? and queries_capped_id >= ? order by capture_time",
                new CappedIdRowMapper(), serverId, transactionType, captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
        mergeInQueries(mergedQueries, rollupLevel, cappedIds);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInTransactionQueries(QueryCollector mergedQueries, long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(
                "select capture_time, queries_capped_id from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where server_id = ?"
                        + " and transaction_type = ? and transaction_name = ? and capture_time > ?"
                        + " and capture_time <= ? and queries_capped_id >= ? order by capture_time",
                new CappedIdRowMapper(), serverId, transactionType, transactionName,
                captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
        mergeInQueries(mergedQueries, rollupLevel, cappedIds);
    }

    @Override
    public List<ErrorPoint> readOverallErrorPoints(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, sum(error_count), sum(transaction_count)"
                        + " from overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time >= ?"
                        + " and capture_time <= ? group by capture_time having sum(error_count) > 0"
                        + " order by capture_time",
                new ErrorPointRowMapper(), serverId, transactionType, captureTimeFrom,
                captureTimeTo);
    }

    @Override
    public List<ErrorPoint> readTransactionErrorPoints(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        return dataSource.query(
                "select capture_time, error_count, transaction_count from"
                        + " transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and transaction_name = ?"
                        + " and capture_time >= ? and capture_time <= ? and error_count > 0"
                        + " order by capture_time",
                new ErrorPointRowMapper(), serverId, transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveOverallQueries(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveOverallSomething("queries_capped_id", serverId, transactionType,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveTransactionQueries(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveTransactionSomething("queries_capped_id", serverId, transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveOverallProfile(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveOverallSomething("profile_tree_capped_id", serverId, transactionType,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveTransactionProfile(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveTransactionSomething("profile_tree_capped_id", serverId, transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    @Override
    public long getDataPointIntervalMillis(long serverId, long captureTimeFrom,
            long captureTimeTo) {
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
    public int getRollupLevelForView(long serverId, long captureTimeFrom, long captureTimeTo) {
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
    public void deleteAll(long serverId) throws SQLException {
        for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
            dataSource.deleteAll("overall_aggregate_rollup_" + castUntainted(i), serverId);
            dataSource.deleteAll("transaction_aggregate_rollup_" + castUntainted(i), serverId);
        }
    }

    void deleteBefore(long serverId, long captureTime, int rollupLevel) throws SQLException {
        dataSource.deleteBefore("overall_aggregate_rollup_" + castUntainted(rollupLevel),
                serverId, captureTime);
        dataSource.deleteBefore("transaction_aggregate_rollup_" + castUntainted(rollupLevel),
                serverId, captureTime);
    }

    private void rollup(long serverId, long lastRollupTime, long curentRollupTime,
            long fixedIntervalMillis, int toRollupLevel, int fromRollupLevel) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        List<Long> rollupTimes = dataSource.query(
                "select distinct " + captureTimeSql + " from overall_aggregate_rollup_"
                        + castUntainted(fromRollupLevel)
                        + " where server_id = ? and capture_time > ? and capture_time <= ?",
                new LongRowMapper(), serverId, lastRollupTime, curentRollupTime);
        for (Long rollupTime : rollupTimes) {
            rollupOneInterval(serverId, rollupTime, fixedIntervalMillis, toRollupLevel,
                    fromRollupLevel);
        }
    }

    private void rollupOneInterval(long serverId, long rollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        dataSource.query(
                "select transaction_type, total_nanos, transaction_count, error_count,"
                        + " total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                        + " total_allocated_bytes, queries_capped_id, profile_tree_capped_id,"
                        + " histogram, root_timers from overall_aggregate_rollup_"
                        + castUntainted(fromRollupLevel) + " where server_id = ?"
                        + " and capture_time > ? and capture_time <= ? order by transaction_type",
                new RollupOverallAggregates(serverId, rollupTime, fromRollupLevel, toRollupLevel),
                serverId, rollupTime - fixedIntervalMillis, rollupTime);
        dataSource.query(
                "select transaction_type, transaction_name, total_nanos, transaction_count,"
                        + " error_count, total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                        + " total_allocated_bytes, queries_capped_id, profile_tree_capped_id,"
                        + " histogram, root_timers from transaction_aggregate_rollup_"
                        + castUntainted(fromRollupLevel) + " where server_id = ?"
                        + " and capture_time > ? and capture_time <= ?"
                        + " order by transaction_type, transaction_name",
                new RollupTransactionAggregates(serverId, rollupTime, fromRollupLevel,
                        toRollupLevel),
                serverId, rollupTime - fixedIntervalMillis, rollupTime);
    }

    private void storeTransactionAggregate(long serverId, long captureTime, String transactionType,
            String transactionName, Aggregate aggregate, int rollupLevel) throws Exception {
        dataSource.update(
                "insert into transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (server_id, transaction_type, transaction_name, capture_time,"
                        + " total_nanos, transaction_count, error_count, total_cpu_nanos,"
                        + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                        + " queries_capped_id, profile_tree_capped_id, histogram, root_timers)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionAggregateBinder(serverId, captureTime, transactionType,
                        transactionName, aggregate, rollupLevel));
    }

    private void storeOverallAggregate(long serverId, long captureTime, String transactionType,
            Aggregate aggregate, int rollupLevel) throws Exception {
        dataSource.update(
                "insert into overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (server_id, transaction_type, capture_time, total_nanos,"
                        + " transaction_count, error_count, total_cpu_nanos, total_blocked_nanos,"
                        + " total_waited_nanos, total_allocated_bytes, queries_capped_id,"
                        + " profile_tree_capped_id, histogram, root_timers)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallAggregateBinder(serverId, captureTime, transactionType, aggregate,
                        rollupLevel));
    }

    // captureTimeFrom is non-inclusive
    private OverallSummary readOverallSummaryInternal(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        OverallSummary summary = dataSource.query("select sum(total_nanos),"
                + " sum(transaction_count) from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where server_id = ? and transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?",
                new OverallSummaryResultSetExtractor(), serverId, transactionType, captureTimeFrom,
                captureTimeTo);
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

    private List<TransactionSummary> readTransactionSummariesInternal(
            TransactionSummaryQuery query, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query(
                "select transaction_name, sum(total_nanos), sum(transaction_count)"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? group by transaction_name order by "
                        + getSortClause(query.sortOrder()) + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.serverId(), query.transactionType(),
                query.from(), query.to(), query.limit() + 1);
    }

    private List<TransactionSummary> readTransactionSummariesInternalSplit(
            TransactionSummaryQuery query, int rollupLevel, long lastRollupTime) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query(
                "select transaction_name, sum(total_nanos), sum(transaction_count)"
                        + " from (select transaction_name, total_nanos, transaction_count"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? union all select transaction_name, total_nanos,"
                        + " transaction_count from transaction_aggregate_rollup_0 where"
                        + " transaction_type = ? and capture_time > ? and capture_time <= ?) t"
                        + " group by transaction_name order by " + getSortClause(query.sortOrder())
                        + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.serverId(), query.transactionType(),
                query.from(), lastRollupTime, query.transactionType(), lastRollupTime, query.to(),
                query.limit() + 1);
    }

    private void mergeInProfiles(ProfileCollector mergedProfile, int rollupLevel,
            List<CappedId> cappedIds) throws IOException {
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            ProfileTree profileTree = rollupCappedDatabases.get(rollupLevel)
                    .readMessage(cappedId.cappedId(), ProfileTree.parser());
            if (profileTree != null) {
                mergedProfile.mergeProfileTree(profileTree);
                mergedProfile.updateLastCaptureTime(captureTime);
            }
        }
    }

    private void mergeInQueries(QueryCollector mergedQueries, int rollupLevel,
            List<CappedId> cappedIds) throws IOException {
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(rollupLevel)
                    .readMessages(cappedId.cappedId(), Aggregate.QueriesByType.parser());
            if (queries != null) {
                mergedQueries.mergeQueries(queries);
                mergedQueries.updateLastCaptureTime(captureTime);
            }
        }
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveOverallSomething(@Untainted String cappedIdColumnName,
            long serverId, String transactionType, long captureTimeFrom, long captureTimeTo)
                    throws Exception {
        int rollupLevel = getRollupLevelForView(serverId, captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists(
                "select 1 from overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ? and " + cappedIdColumnName
                        + " is not null limit 1",
                serverId, transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveTransactionSomething(@Untainted String cappedIdColumnName,
            long serverId, String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        int rollupLevel = getRollupLevelForView(serverId, captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists(
                "select 1 from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where server_id = ? and transaction_type = ? and transaction_name = ?"
                        + " and capture_time > ? and capture_time <= ? and " + cappedIdColumnName
                        + " is not null limit 1",
                serverId, transactionType, transactionName, captureTimeFrom, captureTimeTo);
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

        private final long captureTime;
        private final Aggregate aggregate;
        private final @Nullable Long queriesCappedId;
        private final @Nullable Long profileCappedId;
        private final byte[] histogramBytes;
        private final byte[] rootTimers;

        private AggregateBinder(long captureTime, Aggregate aggregate, int rollupLevel)
                throws IOException {
            this.captureTime = captureTime;
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
        void bindCommon(PreparedStatement preparedStatement, int startIndex) throws SQLException {
            int i = startIndex;
            preparedStatement.setLong(i++, captureTime);
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

        private final long serverId;
        private final String transactionType;

        private OverallAggregateBinder(long serverId, long captureTime, String transactionType,
                Aggregate aggregate, int rollupLevel) throws IOException {
            super(captureTime, aggregate, rollupLevel);
            this.serverId = serverId;
            this.transactionType = transactionType;
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, serverId);
            preparedStatement.setString(2, transactionType);
            bindCommon(preparedStatement, 3);
            preparedStatement.addBatch();
        }
    }

    private class TransactionAggregateBinder extends AggregateBinder
            implements PreparedStatementBinder {

        private final long serverId;
        private final String transactionType;
        private final String transactionName;

        private TransactionAggregateBinder(long serverId, long captureTime, String transactionType,
                String transactionName, Aggregate aggregate, int rollupLevel) throws IOException {
            super(captureTime, aggregate, rollupLevel);
            this.serverId = serverId;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, serverId);
            preparedStatement.setString(2, transactionType);
            preparedStatement.setString(3, transactionName);
            bindCommon(preparedStatement, 4);
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

    private static class CappedIdRowMapper implements RowMapper<CappedId> {
        @Override
        public CappedId mapRow(ResultSet resultSet) throws Exception {
            return ImmutableCappedId.of(resultSet.getLong(1), resultSet.getLong(2));
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CappedId {
        long captureTime();
        long cappedId();
    }

    private static class LongRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    private class RollupOverallAggregates implements ResultSetExtractor</*@Nullable*/Void> {

        private final long serverId;
        private final long rollupCaptureTime;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupOverallAggregates(long serverId, long rollupCaptureTime, int fromRollupLevel,
                int toRollupLevel) {
            this.serverId = serverId;
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Nullable Void extractData(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerQueryType =
                    configRepository.getAdvancedConfig(serverId).maxAggregateQueriesPerQueryType();
            MutableOverallAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                if (curr == null || transactionType != curr.transactionType()) {
                    if (curr != null) {
                        storeOverallAggregate(serverId, rollupCaptureTime, curr.transactionType(),
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel);
                    }
                    curr = ImmutableMutableOverallAggregate.of(transactionType,
                            new MutableAggregate(maxAggregateQueriesPerQueryType));
                }
                merge(curr.aggregate(), resultSet, 2, fromRollupLevel);
            }
            if (curr != null) {
                storeOverallAggregate(serverId, rollupCaptureTime, curr.transactionType(),
                        curr.aggregate().toAggregate(scratchBuffer), toRollupLevel);
            }
            return null;
        }
    }

    private class RollupTransactionAggregates implements ResultSetExtractor</*@Nullable*/Void> {

        private final long serverId;
        private final long rollupCaptureTime;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupTransactionAggregates(long serverId, long rollupCaptureTime,
                int fromRollupLevel, int toRollupLevel) {
            this.serverId = serverId;
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Nullable Void extractData(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerQueryType =
                    configRepository.getAdvancedConfig(serverId).maxAggregateQueriesPerQueryType();
            MutableTransactionAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                String transactionName = checkNotNull(resultSet.getString(2));
                if (curr == null || transactionType != curr.transactionType()
                        || transactionName != curr.transactionName()) {
                    if (curr != null) {
                        storeTransactionAggregate(serverId, rollupCaptureTime,
                                curr.transactionType(), curr.transactionName(),
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel);
                    }
                    curr = ImmutableMutableTransactionAggregate.of(transactionType, transactionName,
                            new MutableAggregate(maxAggregateQueriesPerQueryType));
                }
                merge(curr.aggregate(), resultSet, 3, fromRollupLevel);
            }
            if (curr != null) {
                storeTransactionAggregate(serverId, rollupCaptureTime, curr.transactionType(),
                        curr.transactionName(), curr.aggregate().toAggregate(scratchBuffer),
                        toRollupLevel);
            }
            return null;
        }
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
}
