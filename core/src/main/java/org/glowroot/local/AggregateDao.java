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
package org.glowroot.local;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
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
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.collector.spi.Aggregate;
import org.glowroot.collector.spi.Histogram;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.ImmutableErrorPoint;
import org.glowroot.common.repo.ImmutableOverallErrorSummary;
import org.glowroot.common.repo.ImmutableOverallSummary;
import org.glowroot.common.repo.ImmutableOverviewAggregate;
import org.glowroot.common.repo.ImmutablePercentileAggregate;
import org.glowroot.common.repo.ImmutableTransactionErrorSummary;
import org.glowroot.common.repo.ImmutableTransactionSummary;
import org.glowroot.common.repo.LazyHistogram;
import org.glowroot.common.repo.MutableAggregate;
import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.repo.MutableQuery;
import org.glowroot.common.repo.ProfileCollector;
import org.glowroot.common.repo.QueryCollector;
import org.glowroot.common.repo.Result;
import org.glowroot.common.repo.helper.JsonMarshaller;
import org.glowroot.common.repo.helper.JsonUnmarshaller;
import org.glowroot.common.util.Clock;
import org.glowroot.local.util.CappedDatabase;
import org.glowroot.local.util.CappedDatabase.Unmarshaller;
import org.glowroot.local.util.DataSource;
import org.glowroot.local.util.DataSource.PreparedStatementBinder;
import org.glowroot.local.util.DataSource.ResultSetExtractor;
import org.glowroot.local.util.DataSource.RowMapper;
import org.glowroot.local.util.ImmutableColumn;
import org.glowroot.local.util.ImmutableIndex;
import org.glowroot.local.util.RowMappers;
import org.glowroot.local.util.Schemas.Column;
import org.glowroot.local.util.Schemas.Index;
import org.glowroot.local.util.ScratchBuffer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.local.util.Checkers.castUntainted;

class AggregateDao implements AggregateRepository {

    public static final String OVERWRITTEN = "{\"overwritten\":true}";

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_micros", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_micros", Types.BIGINT),
                    ImmutableColumn.of("total_waited_micros", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_kbytes", Types.BIGINT),
                    ImmutableColumn.of("queries_capped_id", Types.BIGINT), // capped database id
                    // profile json is always from "synthetic root"
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    ImmutableColumn.of("histogram", Types.BLOB),
                    // timers json is always from "synthetic root"
                    ImmutableColumn.of("timers", Types.VARCHAR)); // json data

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("transaction_name", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_micros", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_micros", Types.BIGINT),
                    ImmutableColumn.of("total_waited_micros", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_kbytes", Types.BIGINT),
                    ImmutableColumn.of("queries_capped_id", Types.BIGINT), // capped database id
                    // profile json is always from "synthetic root"
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    ImmutableColumn.of("histogram", Types.BLOB),
                    // timers json is always from "synthetic root"
                    ImmutableColumn.of("timers", Types.VARCHAR)); // json data

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns = ImmutableList.of(
            "capture_time", "transaction_type", "total_micros", "transaction_count", "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name", "total_micros",
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

    void store(Map<String, ? extends Aggregate> overallAggregates,
            Map<String, ? extends Map<String, ? extends Aggregate>> transactionAggregates,
            long captureTime) throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        for (Entry<String, ? extends Aggregate> entry : overallAggregates.entrySet()) {
            storeOverallAggregate(0, entry.getKey(), entry.getValue(), scratchBuffer);
        }
        for (Entry<String, ? extends Map<String, ? extends Aggregate>> outerEntry : transactionAggregates
                .entrySet()) {
            for (Entry<String, ? extends Aggregate> innerEntry : outerEntry.getValue().entrySet()) {
                storeTransactionAggregate(0, outerEntry.getKey(), innerEntry.getKey(),
                        innerEntry.getValue(), scratchBuffer);
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
        return dataSource.query("select capture_time, total_micros, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_kbytes, timers from overall_aggregate_rollup_"
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
                "select capture_time, total_micros, transaction_count, histogram"
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
                "select capture_time, total_micros, transaction_count, total_cpu_micros,"
                        + " total_blocked_micros, total_waited_micros, total_allocated_kbytes,"
                        + " timers from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and transaction_name = ?"
                        + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new OverviewAggregateRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public ImmutableList<PercentileAggregate> readTransactionPercentileAggregates(
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        return dataSource.query(
                "select capture_time, total_micros, transaction_count, histogram"
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
        dataSource.query("select capture_time, profile_capped_id from overall_aggregate_rollup_"
                + castUntainted(rollupLevel) + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? and profile_capped_id >= ?",
                new ProfileMergingResultSetExtractor(mergedProfile, rollupLevel), transactionType,
                captureTimeFrom, captureTimeTo,
                rollupCappedDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    @Override
    public void mergeInTransactionProfile(ProfileCollector mergedProfile, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception {
        dataSource.query(
                "select capture_time, profile_capped_id from transaction_aggregate_rollup_"
                        + castUntainted(rollupLevel) + " where transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and profile_capped_id >= ?",
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
        return shouldHaveOverallSomething("profile_capped_id", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    @Override
    public boolean shouldHaveTransactionProfile(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return shouldHaveTransactionSomething("profile_capped_id", transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
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
                "select transaction_type, total_micros, error_count, transaction_count,"
                        + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                        + " total_allocated_kbytes, queries_capped_id, profile_capped_id,"
                        + " histogram, timers from overall_aggregate_rollup_"
                        + castUntainted(fromRollupLevel)
                        + " where capture_time > ? and capture_time <= ?",
                new OverallRollupResultSetExtractor(rollupTime, fromRollupLevel),
                rollupTime - fixedIntervalMillis, rollupTime);
        if (overallAggregates == null) {
            // data source is closing
            return;
        }
        Map<String, Map<String, MutableAggregate>> transactionAggregates = dataSource.query(
                "select transaction_type, transaction_name, total_micros, error_count,"
                        + " transaction_count, total_cpu_micros, total_blocked_micros,"
                        + " total_waited_micros, total_allocated_kbytes, queries_capped_id,"
                        + " profile_capped_id, histogram, timers from transaction_aggregate_rollup_"
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
            storeOverallAggregate(rollupLevel, entry.getKey(), entry.getValue().toAggregate(),
                    scratchBuffer);
        }
        for (Entry<String, Map<String, MutableAggregate>> outerEntry : transactionAggregates
                .entrySet()) {
            for (Entry<String, MutableAggregate> innerEntry : outerEntry.getValue().entrySet()) {
                storeTransactionAggregate(rollupLevel, outerEntry.getKey(), innerEntry.getKey(),
                        innerEntry.getValue().toAggregate(), scratchBuffer);
            }
        }
    }

    private void storeTransactionAggregate(int rollupLevel, String transactionType,
            String transactionName, Aggregate aggregate, ScratchBuffer scratchBuffer)
                    throws Exception {
        dataSource.update(
                "insert into transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (transaction_type, transaction_name, capture_time, total_micros,"
                        + " error_count, transaction_count, total_cpu_micros, total_blocked_micros,"
                        + " total_waited_micros, total_allocated_kbytes, queries_capped_id,"
                        + " profile_capped_id, histogram, timers) values"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionAggregateBinder(transactionType, transactionName, aggregate,
                        rollupLevel, scratchBuffer));
    }

    private void storeOverallAggregate(int rollupLevel, String transactionType, Aggregate aggregate,
            ScratchBuffer scratchBuffer) throws Exception {
        dataSource.update(
                "insert into overall_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " (transaction_type, capture_time, total_micros, error_count,"
                        + " transaction_count, total_cpu_micros, total_blocked_micros,"
                        + " total_waited_micros, total_allocated_kbytes, queries_capped_id,"
                        + " profile_capped_id, histogram, timers) values"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallAggregateBinder(transactionType, aggregate, rollupLevel, scratchBuffer));
    }

    // captureTimeFrom is non-inclusive
    private OverallSummary readOverallSummaryInternal(String transactionType, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        OverallSummary summary = dataSource.query("select sum(total_micros),"
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
                .totalMicros(overallSummary1.totalMicros() + overallSummary2.totalMicros())
                .transactionCount(
                        overallSummary1.transactionCount() + overallSummary2.transactionCount())
                .build();
    }

    private ImmutableList<TransactionSummary> readTransactionSummariesInternal(
            TransactionSummaryQuery query, int rollupLevel) throws Exception {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query(
                "select transaction_name, sum(total_micros), sum(transaction_count)"
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
                "select transaction_name, sum(total_micros), sum(transaction_count)"
                        + " from (select transaction_name, total_micros, transaction_count"
                        + " from transaction_aggregate_rollup_" + castUntainted(rollupLevel)
                        + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                        + " union all select transaction_name, total_micros, transaction_count"
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
        long totalMicros = resultSet.getLong(i++);
        long errorCount = resultSet.getLong(i++);
        long transactionCount = resultSet.getLong(i++);
        long totalCpuMicros = RowMappers.getNotAvailableAwareLong(resultSet, i++);
        long totalBlockedMicros = RowMappers.getNotAvailableAwareLong(resultSet, i++);
        long totalWaitedMicros = RowMappers.getNotAvailableAwareLong(resultSet, i++);
        long totalAllocatedKBytes = RowMappers.getNotAvailableAwareLong(resultSet, i++);
        Long queriesCappedId = RowMappers.getLong(resultSet, i++);
        Long profileCappedId = RowMappers.getLong(resultSet, i++);
        byte[] histogram = checkNotNull(resultSet.getBytes(i++));
        String timers = checkNotNull(resultSet.getString(i++));

        mergedAggregate.addTotalMicros(totalMicros);
        mergedAggregate.addErrorCount(errorCount);
        mergedAggregate.addTransactionCount(transactionCount);
        mergedAggregate.addTotalCpuMicros(totalCpuMicros);
        mergedAggregate.addTotalBlockedMicros(totalBlockedMicros);
        mergedAggregate.addTotalWaitedMicros(totalWaitedMicros);
        mergedAggregate.addTotalAllocatedKBytes(totalAllocatedKBytes);
        mergedAggregate.addHistogram(histogram);
        mergedAggregate.addTimers(JsonUnmarshaller.unmarshalAggregateTimers(timers));
        if (queriesCappedId != null) {
            Map<String, List<MutableQuery>> queries =
                    readQueries(rollupCappedDatabases.get(fromRollupLevel), queriesCappedId);
            if (queries != null) {
                mergedAggregate.addQueries(queries);
            }
        }
        if (profileCappedId != null) {
            MutableProfileNode profile =
                    readProfile(rollupCappedDatabases.get(fromRollupLevel), profileCappedId);
            if (profile != null) {
                mergedAggregate.addProfile(profile);
            }
        }
    }

    static long getSafeRollupTime(long captureTime, long intervalMillis) {
        return (long) Math.floor(captureTime / (double) intervalMillis) * intervalMillis;
    }

    private static @Untainted String getSortClause(TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return "sum(total_micros) desc";
            case AVERAGE_TIME:
                return "sum(total_micros) / sum(transaction_count) desc";
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

    private static @Nullable Map<String, List<MutableQuery>> readQueries(
            CappedDatabase cappedDatabase, long cappedId) throws IOException {
        return cappedDatabase.unmarshal(cappedId,
                new Unmarshaller<Map<String, List<MutableQuery>>>() {
                    @Override
                    public Map<String, List<MutableQuery>> unmarshal(Reader reader)
                            throws IOException {
                        return JsonUnmarshaller.unmarshalQueries(reader);
                    }
                });
    }

    private static @Nullable MutableProfileNode readProfile(CappedDatabase cappedDatabase,
            long cappedId) throws IOException {
        return cappedDatabase.unmarshal(cappedId, new Unmarshaller<MutableProfileNode>() {
            @Override
            public MutableProfileNode unmarshal(Reader reader) throws IOException {
                return JsonUnmarshaller.unmarshalProfile(reader);
            }
        });
    }

    private class AggregateBinder {

        private final Aggregate aggregate;
        private final @Nullable Long queriesCappedId;
        private final @Nullable Long profileCappedId;
        private final byte[] histogramBytes;
        private final String timers;

        private AggregateBinder(Aggregate aggregate, int rollupLevel, ScratchBuffer scratchBuffer)
                throws IOException {
            this.aggregate = aggregate;

            String queries = JsonMarshaller.marshal(aggregate.queries());
            if (queries == null) {
                queriesCappedId = null;
            } else {
                queriesCappedId = rollupCappedDatabases.get(rollupLevel).write(
                        CharSource.wrap(queries), RollupCappedDatabaseStats.AGGREGATE_QUERIES);
            }

            org.glowroot.collector.spi.ProfileNode syntheticRootProfileNode =
                    aggregate.syntheticRootProfileNode();
            if (syntheticRootProfileNode == null) {
                profileCappedId = null;
            } else {
                profileCappedId = rollupCappedDatabases.get(rollupLevel).write(
                        CharSource.wrap(JsonMarshaller.marshal(syntheticRootProfileNode)),
                        RollupCappedDatabaseStats.AGGREGATE_PROFILES);
            }

            Histogram histogram = aggregate.histogram();
            ByteBuffer buffer = scratchBuffer.getBuffer(histogram.getNeededByteBufferCapacity());
            buffer.clear();
            histogram.encodeIntoByteBuffer(buffer);
            int size = buffer.position();
            buffer.flip();
            histogramBytes = new byte[size];
            buffer.get(histogramBytes, 0, size);

            timers = JsonMarshaller.marshal(aggregate.syntheticRootTimerNode());
        }

        // minimal work inside this method as it is called with active connection
        void bindCommon(PreparedStatement preparedStatement, int startIndex) throws Exception {
            int i = startIndex;
            preparedStatement.setLong(i++, aggregate.captureTime());
            preparedStatement.setLong(i++, aggregate.totalMicros());
            preparedStatement.setLong(i++, aggregate.errorCount());
            preparedStatement.setLong(i++, aggregate.transactionCount());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++, aggregate.totalCpuMicros());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++,
                    aggregate.totalBlockedMicros());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++,
                    aggregate.totalWaitedMicros());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++,
                    aggregate.totalAllocatedKBytes());
            RowMappers.setLong(preparedStatement, i++, queriesCappedId);
            RowMappers.setLong(preparedStatement, i++, profileCappedId);

            preparedStatement.setBytes(i++, histogramBytes);
            preparedStatement.setString(i++, timers);
        }
    }

    private class OverallAggregateBinder extends AggregateBinder
            implements PreparedStatementBinder {

        private final String transactionType;

        private OverallAggregateBinder(String transactionType, Aggregate aggregate, int rollupLevel,
                ScratchBuffer scratchBuffer) throws IOException {
            super(aggregate, rollupLevel, scratchBuffer);
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
                Aggregate aggregate, int rollupLevel, ScratchBuffer scratchBuffer)
                        throws IOException {
            super(aggregate, rollupLevel, scratchBuffer);
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
                    .totalMicros(resultSet.getLong(1))
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
                    .totalMicros(resultSet.getLong(2))
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
                    .totalMicros(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .totalCpuMicros(resultSet.getLong(i++))
                    .totalBlockedMicros(resultSet.getLong(i++))
                    .totalWaitedMicros(resultSet.getLong(i++))
                    .totalAllocatedKBytes(resultSet.getLong(i++));
            String timers = checkNotNull(resultSet.getString(i++));
            builder.syntheticRootTimer(JsonUnmarshaller.unmarshalAggregateTimers(timers));
            return builder.build();
        }
    }

    private static class PercentileAggregateRowMapper implements RowMapper<PercentileAggregate> {

        @Override
        public PercentileAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutablePercentileAggregate.Builder builder = ImmutablePercentileAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalMicros(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++));
            byte[] histogramBytes = checkNotNull(resultSet.getBytes(i++));
            LazyHistogram histogram = new LazyHistogram();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(histogramBytes));
            builder.histogram(histogram);
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
                MutableProfileNode profile =
                        readProfile(rollupCappedDatabases.get(rollupLevel), resultSet.getLong(2));
                if (profile != null) {
                    mergedProfile.mergeSyntheticRootNode(profile);
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
                Map<String, List<MutableQuery>> queries =
                        readQueries(rollupCappedDatabases.get(rollupLevel), resultSet.getLong(2));
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
