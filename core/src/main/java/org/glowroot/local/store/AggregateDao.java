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
package org.glowroot.local.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateTimer;
import org.glowroot.collector.ErrorPoint;
import org.glowroot.collector.ErrorSummary;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.ProfileAggregate;
import org.glowroot.collector.QueryAggregate;
import org.glowroot.collector.QueryComponent;
import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ObjectMappers;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.transaction.model.ProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.Checkers.castUntainted;

public class AggregateDao {

    public static final String OVERWRITTEN = "{\"overwritten\":true}";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    Column.of("transaction_type", Types.VARCHAR),
                    Column.of("capture_time", Types.BIGINT),
                    Column.of("total_micros", Types.BIGINT),
                    Column.of("error_count", Types.BIGINT),
                    Column.of("transaction_count", Types.BIGINT),
                    Column.of("total_cpu_micros", Types.BIGINT),
                    Column.of("total_blocked_micros", Types.BIGINT),
                    Column.of("total_waited_micros", Types.BIGINT),
                    Column.of("total_allocated_kbytes", Types.BIGINT),
                    Column.of("queries_capped_id", Types.BIGINT), // capped database id
                    // profile json is always from "synthetic root"
                    Column.of("profile_capped_id", Types.BIGINT), // capped database id
                    Column.of("histogram", Types.BLOB),
                    // timers json is always from "synthetic root"
                    Column.of("timers", Types.VARCHAR)); // json data

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    Column.of("transaction_type", Types.VARCHAR),
                    Column.of("transaction_name", Types.VARCHAR),
                    Column.of("capture_time", Types.BIGINT),
                    Column.of("total_micros", Types.BIGINT),
                    Column.of("error_count", Types.BIGINT),
                    Column.of("transaction_count", Types.BIGINT),
                    Column.of("total_cpu_micros", Types.BIGINT),
                    Column.of("total_blocked_micros", Types.BIGINT),
                    Column.of("total_waited_micros", Types.BIGINT),
                    Column.of("total_allocated_kbytes", Types.BIGINT),
                    Column.of("queries_capped_id", Types.BIGINT), // capped database id
                    // profile json is always from "synthetic root"
                    Column.of("profile_capped_id", Types.BIGINT), // capped database id
                    Column.of("histogram", Types.BLOB),
                    // timers json is always from "synthetic root"
                    Column.of("timers", Types.VARCHAR)); // json data

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "total_micros",
                    "transaction_count", "error_count");
    private static final ImmutableList<Index> overallAggregateIndexes = ImmutableList.<Index>of(
            Index.of("overall_aggregate_idx", overallAggregateIndexColumns));
    private static final ImmutableList<Index> overallAggregateRollup1Indexes =
            ImmutableList.<Index>of(Index.of("overall_aggregate_rollup_1_idx",
                    overallAggregateIndexColumns));
    private static final ImmutableList<Index> overallAggregateRollup2Indexes =
            ImmutableList.<Index>of(Index.of("overall_aggregate_rollup_2_idx",
                    overallAggregateIndexColumns));

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name",
                    "total_micros", "transaction_count", "error_count");
    private static final ImmutableList<Index> transactionAggregateIndexes =
            ImmutableList.<Index>of(Index.of("transaction_aggregate_idx",
                    transactionAggregateIndexColumns));
    private static final ImmutableList<Index> transactionAggregateRollup1Indexes =
            ImmutableList.<Index>of(Index.of("transaction_aggregate_rollup_1_idx",
                    transactionAggregateIndexColumns));
    private static final ImmutableList<Index> transactionAggregateRollup2Indexes =
            ImmutableList.<Index>of(Index.of("transaction_aggregate_rollup_2_idx",
                    transactionAggregateIndexColumns));

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupDatabases;
    private final ConfigService configService;

    private final long fixedRollup1Millis;
    private final long fixedRollup2Millis;
    private final long rollup1ViewThresholdMillis;
    private final long rollup2ViewThresholdMillis;

    private volatile long lastRollup1Time;
    private volatile long lastRollup2Time;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupDatabases,
            ConfigService configService, long fixedRollup1Seconds, long fixedRollup2Seconds)
                    throws SQLException {
        this.dataSource = dataSource;
        this.rollupDatabases = rollupDatabases;
        this.configService = configService;
        fixedRollup1Millis = fixedRollup1Seconds * 1000;
        fixedRollup2Millis = fixedRollup2Seconds * 1000;
        // default rollup1 is 5 minutes, making default rollup1 view threshold 1 hour
        rollup1ViewThresholdMillis = fixedRollup1Millis * 12;
        // default rollup2 is 30 minutes, making default rollup1 view threshold 8 hours
        rollup2ViewThresholdMillis = fixedRollup2Millis * 16;

        // upgrade from 0.8.3 to 0.8.4
        if (dataSource.tableExists("overall_aggregate")) {
            dataSource.execute(
                    "alter table overall_aggregate rename to overall_aggregate_rollup_0");
            dataSource.execute(
                    "alter table transaction_aggregate rename to transaction_aggregate_rollup_0");
        }

        dataSource.syncTable("overall_aggregate_rollup_0", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate_rollup_0", overallAggregateIndexes);
        dataSource.syncTable("transaction_aggregate_rollup_0", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate_rollup_0", transactionAggregateIndexes);
        dataSource.syncTable("overall_aggregate_rollup_1", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate_rollup_1", overallAggregateRollup1Indexes);
        dataSource.syncTable("transaction_aggregate_rollup_1", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate_rollup_1",
                transactionAggregateRollup1Indexes);
        dataSource.syncTable("overall_aggregate_rollup_2", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate_rollup_2", overallAggregateRollup2Indexes);
        dataSource.syncTable("transaction_aggregate_rollup_2", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate_rollup_2",
                transactionAggregateRollup2Indexes);

        // don't need last_rollup_times table like in GaugePointDao since there is already index
        // on capture_time so these queries are relatively fast
        lastRollup1Time = dataSource.queryForLong(
                "select ifnull(max(capture_time), 0) from overall_aggregate_rollup_1");
        lastRollup2Time = dataSource.queryForLong(
                "select ifnull(max(capture_time), 0) from overall_aggregate_rollup_2");

        // TODO initial rollup in case store is not called in a reasonable time
    }

    void store(final List<Aggregate> overallAggregates, List<Aggregate> transactionAggregates,
            long captureTime) throws Exception {
        storeAtRollupLevel(overallAggregates, transactionAggregates, 0);
        synchronized (rollupLock) {
            long rollup1Time = (long) Math.floor(captureTime / (double) fixedRollup1Millis)
                    * fixedRollup1Millis;
            if (rollup1Time > lastRollup1Time) {
                rollup(lastRollup1Time, rollup1Time, fixedRollup1Millis, 1, 0);
                lastRollup1Time = rollup1Time;
            }
            long rollup2Time = (long) Math.floor(captureTime / (double) fixedRollup2Millis)
                    * fixedRollup2Millis;
            if (rollup2Time > lastRollup2Time) {
                rollup(lastRollup2Time, rollup2Time, fixedRollup2Millis, 2, 1);
                lastRollup2Time = rollup2Time;
            }
        }
    }

    // captureTimeFrom is non-inclusive
    public TransactionSummary readOverallSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        // may need to aggregate some non-rolled up data
        if (rollupLevel == 1 && captureTimeTo > lastRollup1Time) {
            TransactionSummary overallSummary = readOverallSummaryInternal(transactionType,
                    captureTimeFrom, lastRollup1Time, rollupLevel);
            TransactionSummary sinceLastRollupSummary =
                    readOverallSummaryInternal(transactionType, lastRollup1Time, captureTimeTo, 0);
            return combineOverallSummaries(overallSummary, sinceLastRollupSummary);
        } else if (rollupLevel == 2 && captureTimeTo > lastRollup2Time) {
            TransactionSummary overallSummary = readOverallSummaryInternal(transactionType,
                    captureTimeFrom, lastRollup2Time, rollupLevel);
            TransactionSummary sinceLastRollupSummary =
                    readOverallSummaryInternal(transactionType, lastRollup2Time, captureTimeTo, 0);
            return combineOverallSummaries(overallSummary, sinceLastRollupSummary);
        }
        return readOverallSummaryInternal(transactionType, captureTimeFrom, captureTimeTo,
                rollupLevel);
    }

    // query.from() is non-inclusive
    public QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws SQLException {
        int rollupLevel = getRollupLevelForView(query.from(), query.to());
        ImmutableList<TransactionSummary> summaries;
        // may need to aggregate some non-rolled up data
        if (rollupLevel == 1 && query.to() > lastRollup1Time) {
            summaries = readTransactionSummariesInternal2(query, rollupLevel, lastRollup1Time);
        } else if (rollupLevel == 2 && query.to() > lastRollup2Time) {
            summaries = readTransactionSummariesInternal2(query, rollupLevel, lastRollup2Time);
        } else {
            summaries = readTransactionSummariesInternal(query, rollupLevel);
        }
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summaries, query.limit());
    }

    // captureTimeFrom is non-inclusive
    public ErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo, @Untainted int rollupLevel) throws SQLException {
        ErrorSummary result = dataSource.query("select sum(error_count), sum(transaction_count)"
                + " from overall_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?",
                new OverallErrorSummaryResultSetExtractor(), transactionType, captureTimeFrom,
                captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return ErrorSummary.builder().build();
        } else {
            return result;
        }
    }

    // captureTimeFrom is non-inclusive
    public QueryResult<ErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query,
            @Untainted int rollupLevel) throws SQLException {
        ImmutableList<ErrorSummary> summary = dataSource.query("select transaction_name,"
                + " sum(error_count), sum(transaction_count) from transaction_aggregate_rollup_"
                + rollupLevel + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? group by transaction_type, transaction_name"
                + " having sum(error_count) > 0 order by " + getSortClause(query.sortOrder())
                + ", transaction_type, transaction_name limit ?", new ErrorSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summary, query.limit());
    }

    public ImmutableList<Aggregate> readOverallAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, @Untainted int rollupLevel)
                    throws SQLException {
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, histogram, timers"
                + " from overall_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new AggregateRowMapper(transactionType, null), transactionType, captureTimeFrom,
                captureTimeTo);
    }

    public ImmutableList<Aggregate> readTransactionAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            @Untainted int rollupLevel) throws SQLException {
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, histogram, timers"
                + " from transaction_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and transaction_name = ? and capture_time >= ?"
                + " and capture_time <= ? order by capture_time",
                new AggregateRowMapper(transactionType, transactionName), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<QueryAggregate> readOverallQueryAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, @Untainted int rollupLevel)
                    throws SQLException {
        return dataSource.query("select capture_time, queries_capped_id"
                + " from overall_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and capture_time > ? and capture_time <= ? and queries_capped_id >= ?",
                new QueryAggregateRowMapper(rollupLevel), transactionType, captureTimeFrom,
                captureTimeTo, rollupDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<QueryAggregate> readTransactionQueryAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            @Untainted int rollupLevel) throws SQLException {
        return dataSource.query("select capture_time, queries_capped_id"
                + " from transaction_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and queries_capped_id >= ?",
                new QueryAggregateRowMapper(rollupLevel), transactionType, transactionName,
                captureTimeFrom, captureTimeTo,
                rollupDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<ProfileAggregate> readOverallProfileAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, @Untainted int rollupLevel)
                    throws SQLException {
        return dataSource.query("select capture_time, profile_capped_id"
                + " from overall_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and capture_time > ? and capture_time <= ? and profile_capped_id >= ?",
                new ProfileAggregateRowMapper(rollupLevel), transactionType, captureTimeFrom,
                captureTimeTo, rollupDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<ProfileAggregate> readTransactionProfileAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            @Untainted int rollupLevel) throws SQLException {
        return dataSource.query("select capture_time, profile_capped_id"
                + " from transaction_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and profile_capped_id >= ?",
                new ProfileAggregateRowMapper(rollupLevel), transactionType, transactionName,
                captureTimeFrom, captureTimeTo,
                rollupDatabases.get(rollupLevel).getSmallestNonExpiredId());
    }

    public ImmutableList<ErrorPoint> readOverallErrorPoints(String transactionType,
            long captureTimeFrom, long captureTimeTo, @Untainted int rollupLevel)
                    throws SQLException {
        return dataSource.query("select capture_time, sum(error_count), sum(transaction_count)"
                + " from overall_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? group by capture_time"
                + " having sum(error_count) > 0 order by capture_time", new ErrorPointRowMapper(),
                transactionType, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            @Untainted int rollupLevel) throws SQLException {
        return dataSource.query("select capture_time, error_count, transaction_count from"
                + " transaction_aggregate_rollup_" + rollupLevel + " where transaction_type = ?"
                + " and transaction_name = ? and capture_time >= ? and capture_time <= ?"
                + " and error_count > 0", new ErrorPointRowMapper(), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallQueries(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return shouldHaveOverallSomething("queries_capped_id", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionQueries(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return shouldHaveTransactionSomething("queries_capped_id", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallProfile(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return shouldHaveOverallSomething("profile_capped_id", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionProfile(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return shouldHaveTransactionSomething("profile_capped_id", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    public long getRollup1ViewThresholdMillis() {
        return rollup1ViewThresholdMillis;
    }

    public long getRollup2ViewThresholdMillis() {
        return rollup2ViewThresholdMillis;
    }

    // captureTimeFrom is non-inclusive
    public @Untainted int getRollupLevelForView(long captureTimeFrom, long captureTimeTo) {
        long millis = captureTimeTo - captureTimeFrom;
        if (millis >= rollup2ViewThresholdMillis) {
            return 2;
        } else if (millis >= rollup1ViewThresholdMillis) {
            return 1;
        } else {
            return 0;
        }
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table overall_aggregate_rollup_0");
        dataSource.execute("truncate table overall_aggregate_rollup_1");
        dataSource.execute("truncate table overall_aggregate_rollup_2");
        dataSource.execute("truncate table transaction_aggregate_rollup_0");
        dataSource.execute("truncate table transaction_aggregate_rollup_1");
        dataSource.execute("truncate table transaction_aggregate_rollup_2");
    }

    void deleteBefore(long captureTime, @Untainted int rollupLevel) throws SQLException {
        dataSource.deleteBefore("overall_aggregate_rollup_" + rollupLevel, captureTime);
        dataSource.deleteBefore("transaction_aggregate_rollup_" + rollupLevel, captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime, long fixedRollupMillis,
            @Untainted int toRollupLevel, @Untainted int fromRollupLevel) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil(capture_time / " + fixedRollupMillis + ".0) * "
                + fixedRollupMillis);
        List<Long> rollupTimes = dataSource.query("select distinct " + captureTimeSql
                + " from overall_aggregate_rollup_" + fromRollupLevel + " where capture_time > ?"
                + " and capture_time <= ?", new LongRowMapper(), lastRollupTime, curentRollupTime);
        for (Long rollupTime : rollupTimes) {
            rollupOneInterval(rollupTime, fixedRollupMillis, toRollupLevel, fromRollupLevel);
        }
    }

    private void rollupOneInterval(long rollupTime, long fixedRollupMillis,
            @Untainted int toRollupLevel, @Untainted int fromRollupLevel) throws Exception {
        List<Aggregate> overallAggregates = dataSource.query("select transaction_type,"
                + " total_micros, error_count, transaction_count, total_cpu_micros,"
                + " total_blocked_micros, total_waited_micros, total_allocated_kbytes,"
                + " queries_capped_id, profile_capped_id, histogram, timers"
                + " from overall_aggregate_rollup_" + fromRollupLevel + " where capture_time > ?"
                + " and capture_time <= ?",
                new OverallRollupResultSetExtractor(rollupTime, fromRollupLevel),
                rollupTime - fixedRollupMillis, rollupTime);
        if (overallAggregates == null) {
            // data source is closing
            return;
        }
        List<Aggregate> transactionAggregates = dataSource.query("select transaction_type,"
                + " transaction_name, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_kbytes, queries_capped_id, profile_capped_id, histogram,"
                + " timers from transaction_aggregate_rollup_" + fromRollupLevel
                + " where capture_time > ? and capture_time <= ?",
                new TransactionRollupResultSetExtractor(rollupTime, fromRollupLevel),
                rollupTime - fixedRollupMillis, rollupTime);
        if (transactionAggregates == null) {
            // data source is closing
            return;
        }
        storeAtRollupLevel(overallAggregates, transactionAggregates, toRollupLevel);
    }

    private void storeAtRollupLevel(List<Aggregate> overallAggregates,
            List<Aggregate> transactionAggregates, @Untainted int rollupLevel) throws Exception {
        dataSource.batchUpdate("insert into overall_aggregate_rollup_" + rollupLevel
                + " (transaction_type, capture_time, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_kbytes, queries_capped_id, profile_capped_id, histogram,"
                + " timers) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallBatchAdder(overallAggregates, rollupLevel));
        dataSource.batchUpdate("insert into transaction_aggregate_rollup_" + rollupLevel
                + " (transaction_type, transaction_name, capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, queries_capped_id,"
                + " profile_capped_id, histogram, timers)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionBatchAdder(transactionAggregates, rollupLevel));
    }

    // captureTimeFrom is non-inclusive
    private TransactionSummary readOverallSummaryInternal(String transactionType,
            long captureTimeFrom, long captureTimeTo, @Untainted int rollupLevel)
                    throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        TransactionSummary summary = dataSource.query("select sum(total_micros),"
                + " sum(transaction_count) from overall_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?",
                new OverallSummaryResultSetExtractor(), transactionType, captureTimeFrom,
                captureTimeTo);
        if (summary == null) {
            // this can happen if datasource is in the middle of closing
            return TransactionSummary.builder().build();
        } else {
            return summary;
        }
    }

    private TransactionSummary combineOverallSummaries(TransactionSummary overallSummary1,
            TransactionSummary overallSummary2) {
        return TransactionSummary.builder()
                .totalMicros(overallSummary1.totalMicros() + overallSummary2.totalMicros())
                .transactionCount(overallSummary1.transactionCount()
                        + overallSummary2.transactionCount())
                .build();
    }

    private ImmutableList<TransactionSummary> readTransactionSummariesInternal(
            TransactionSummaryQuery query, int rollupLevel) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query("select transaction_name, sum(total_micros), sum(transaction_count)"
                + " from transaction_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " group by transaction_name order by " + getSortClause(query.sortOrder())
                + ", transaction_name limit ?", new TransactionSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(), query.limit() + 1);
    }

    private ImmutableList<TransactionSummary> readTransactionSummariesInternal2(
            TransactionSummaryQuery query, int rollupLevel, long lastRollupTime)
                    throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        return dataSource.query("select transaction_name, sum(total_micros), sum(transaction_count)"
                + " from (select transaction_name, total_micros, transaction_count"
                + " from transaction_aggregate_rollup_" + rollupLevel
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " union all select transaction_name, total_micros, transaction_count"
                + " from transaction_aggregate_rollup_0 where transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?) group by transaction_name order by "
                + getSortClause(query.sortOrder()) + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.transactionType(), query.from(),
                lastRollupTime, query.transactionType(), lastRollupTime, query.to(),
                query.limit() + 1);
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveOverallSomething(@Untainted String cappedIdColumnName,
            String transactionType, long captureTimeFrom, long captureTimeTo) throws SQLException {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists("select 1 from overall_aggregate_rollup_"
                + rollupLevel + " where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ? and " + cappedIdColumnName + " is not null limit 1",
                transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    private boolean shouldHaveTransactionSomething(@Untainted String cappedIdColumnName,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        int rollupLevel = getRollupLevelForView(captureTimeFrom, captureTimeTo);
        return dataSource.queryForExists("select 1 from transaction_aggregate_rollup_"
                + rollupLevel + " where transaction_type = ?"
                + " and transaction_name = ? and capture_time > ? and capture_time <= ? and  "
                + cappedIdColumnName + " is not null limit 1", transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    private void bindCommon(PreparedStatement preparedStatement, Aggregate overallAggregate,
            int startIndex, int rollupLevel) throws Exception {
        Long queriesCappedId = null;
        String queries = overallAggregate.queries();
        if (queries != null) {
            queriesCappedId = rollupDatabases.get(rollupLevel).write(CharSource.wrap(queries),
                    AggregateDetailDatabaseStats.AGGREGATE_QUERIES);
        }
        Long profileCappedId = null;
        String profile = overallAggregate.profile();
        if (profile != null) {
            profileCappedId = rollupDatabases.get(rollupLevel).write(CharSource.wrap(profile),
                    AggregateDetailDatabaseStats.AGGREGATE_PROFILES);
        }
        int i = startIndex;
        preparedStatement.setLong(i++, overallAggregate.captureTime());
        preparedStatement.setLong(i++, overallAggregate.totalMicros());
        preparedStatement.setLong(i++, overallAggregate.errorCount());
        preparedStatement.setLong(i++, overallAggregate.transactionCount());
        RowMappers.setLong(preparedStatement, i++, overallAggregate.totalCpuMicros());
        RowMappers.setLong(preparedStatement, i++, overallAggregate.totalBlockedMicros());
        RowMappers.setLong(preparedStatement, i++, overallAggregate.totalWaitedMicros());
        RowMappers.setLong(preparedStatement, i++, overallAggregate.totalAllocatedKBytes());
        RowMappers.setLong(preparedStatement, i++, queriesCappedId);
        RowMappers.setLong(preparedStatement, i++, profileCappedId);
        preparedStatement.setBytes(i++, overallAggregate.histogram());
        preparedStatement.setString(i++, overallAggregate.timers());
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

    private static @Untainted String getSortClause(ErrorSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case ERROR_COUNT:
                return "sum(error_count) desc";
            case ERROR_RATE:
                return "sum(error_count) / sum(transaction_count) desc";
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    @Value.Immutable
    public abstract static class TransactionSummaryQueryBase {
        public abstract String transactionType();
        // from is non-inclusive
        public abstract long from();
        public abstract long to();
        public abstract TransactionSummarySortOrder sortOrder();
        public abstract int limit();
    }

    @Value.Immutable
    public abstract static class ErrorSummaryQueryBase {
        public abstract String transactionType();
        // from is non-inclusive
        public abstract long from();
        public abstract long to();
        public abstract ErrorSummarySortOrder sortOrder();
        public abstract int limit();
    }

    public static enum TransactionSummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public static enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }

    private class OverallBatchAdder implements BatchAdder {

        private final List<Aggregate> overallAggregates;
        private final int rollupLevel;

        private OverallBatchAdder(List<Aggregate> overallAggregates, int rollupLevel) {
            this.overallAggregates = overallAggregates;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public void addBatches(PreparedStatement preparedStatement) throws Exception {
            for (Aggregate overallAggregate : overallAggregates) {
                preparedStatement.setString(1, overallAggregate.transactionType());
                bindCommon(preparedStatement, overallAggregate, 2, rollupLevel);
                preparedStatement.addBatch();
            }
        }
    }

    private class TransactionBatchAdder implements BatchAdder {

        private final List<Aggregate> transactionAggregates;
        private final int rollupLevel;

        private TransactionBatchAdder(List<Aggregate> transactionAggregates, int rollupLevel) {
            this.transactionAggregates = transactionAggregates;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public void addBatches(PreparedStatement preparedStatement) throws Exception {
            for (Aggregate transactionAggregate : transactionAggregates) {
                preparedStatement.setString(1, transactionAggregate.transactionType());
                preparedStatement.setString(2, transactionAggregate.transactionName());
                bindCommon(preparedStatement, transactionAggregate, 3, rollupLevel);
                preparedStatement.addBatch();
            }
        }
    }

    private static class OverallSummaryResultSetExtractor implements
            ResultSetExtractor<TransactionSummary> {
        @Override
        public TransactionSummary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return TransactionSummary.builder()
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
            return TransactionSummary.builder()
                    .transactionName(transactionName)
                    .totalMicros(resultSet.getLong(2))
                    .transactionCount(resultSet.getLong(3))
                    .build();
        }
    }

    private static class OverallErrorSummaryResultSetExtractor implements
            ResultSetExtractor<ErrorSummary> {
        @Override
        public ErrorSummary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ErrorSummary.builder()
                    .errorCount(resultSet.getLong(1))
                    .transactionCount(resultSet.getLong(2))
                    .build();
        }
    }

    private static class ErrorSummaryRowMapper implements RowMapper<ErrorSummary> {
        @Override
        public ErrorSummary mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            if (transactionName == null) {
                // transaction_name should never be null
                throw new SQLException("Found null transaction_name in transaction_aggregate");
            }
            return ErrorSummary.builder()
                    .transactionName(transactionName)
                    .errorCount(resultSet.getLong(2))
                    .transactionCount(resultSet.getLong(3))
                    .build();
        }
    }

    private static class AggregateRowMapper implements RowMapper<Aggregate> {

        private final String transactionType;
        private final @Nullable String transactionName;

        private AggregateRowMapper(String transactionType, @Nullable String transactionName) {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }

        @Override
        public Aggregate mapRow(ResultSet resultSet) throws SQLException {
            int i = 1;
            return Aggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(resultSet.getLong(i++))
                    .totalMicros(resultSet.getLong(i++))
                    .errorCount(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .totalCpuMicros(resultSet.getLong(i++))
                    .totalBlockedMicros(resultSet.getLong(i++))
                    .totalWaitedMicros(resultSet.getLong(i++))
                    .totalAllocatedKBytes(resultSet.getLong(i++))
                    .histogram(checkNotNull(resultSet.getBytes(i++)))
                    .timers(checkNotNull(resultSet.getString(i++)))
                    .build();
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<ErrorPoint> {
        @Override
        public ErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            long transactionCount = resultSet.getLong(3);
            return ErrorPoint.of(captureTime, errorCount, transactionCount);
        }
    }

    private class QueryAggregateRowMapper implements RowMapper<QueryAggregate> {

        private final int rollupLevel;

        public QueryAggregateRowMapper(int rollupLevel) {
            this.rollupLevel = rollupLevel;
        }

        @Override
        public QueryAggregate mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            CharSource queries =
                    rollupDatabases.get(rollupLevel).read(resultSet.getLong(2), OVERWRITTEN);
            return QueryAggregate.of(captureTime, queries);
        }
    }

    private class ProfileAggregateRowMapper implements RowMapper<ProfileAggregate> {

        private final int rollupLevel;

        public ProfileAggregateRowMapper(int rollupLevel) {
            this.rollupLevel = rollupLevel;
        }

        @Override
        public ProfileAggregate mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            CharSource profile =
                    rollupDatabases.get(rollupLevel).read(resultSet.getLong(2), OVERWRITTEN);
            return ProfileAggregate.of(captureTime, profile);
        }
    }

    private static class LongRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    private abstract class RollupResultSetExtractor implements ResultSetExtractor<List<Aggregate>> {
        void merge(MergedAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex,
                int fromRollupLevel) throws Exception {
            int i = startColumnIndex;
            long totalMicros = resultSet.getLong(i++);
            long errorCount = resultSet.getLong(i++);
            long transactionCount = resultSet.getLong(i++);
            Long totalCpuMicros = resultSet.getLong(i++);
            Long totalBlockedMicros = RowMappers.getLong(resultSet, i++);
            Long totalWaitedMicros = RowMappers.getLong(resultSet, i++);
            Long totalAllocatedKBytes = RowMappers.getLong(resultSet, i++);
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
            mergedAggregate.addTimers(timers);
            if (queriesCappedId != null) {
                String queriesContent = rollupDatabases.get(fromRollupLevel)
                        .read(queriesCappedId, AggregateDao.OVERWRITTEN).read();
                if (!queriesContent.equals(AggregateDao.OVERWRITTEN)) {
                    mergedAggregate.addQueries(queriesContent);
                }
            }
            if (profileCappedId != null) {
                String profileContent = rollupDatabases.get(fromRollupLevel)
                        .read(profileCappedId, AggregateDao.OVERWRITTEN).read();
                if (!profileContent.equals(AggregateDao.OVERWRITTEN)) {
                    mergedAggregate.addProfile(profileContent);
                }
            }
        }
    }

    private class OverallRollupResultSetExtractor extends RollupResultSetExtractor {

        private final long rollupCaptureTime;
        private final int fromRollupLevel;

        private OverallRollupResultSetExtractor(long rollupCaptureTime, int fromRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
        }

        @Override
        public List<Aggregate> extractData(ResultSet resultSet) throws Exception {
            Map<String, MergedAggregate> mergedAggregates = Maps.newHashMap();
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                MergedAggregate mergedAggregate = mergedAggregates.get(transactionType);
                if (mergedAggregate == null) {
                    mergedAggregate = new MergedAggregate(rollupCaptureTime, transactionType, null,
                            configService.getAdvancedConfig().maxAggregateQueriesPerQueryType());
                    mergedAggregates.put(transactionType, mergedAggregate);
                }
                merge(mergedAggregate, resultSet, 2, fromRollupLevel);
            }
            List<Aggregate> aggregates = Lists.newArrayList();
            ScratchBuffer scratchBuffer = new ScratchBuffer();
            for (MergedAggregate mergedAggregate : mergedAggregates.values()) {
                aggregates.add(mergedAggregate.toAggregate(scratchBuffer));
            }
            return aggregates;
        }
    }

    private class TransactionRollupResultSetExtractor extends RollupResultSetExtractor {

        private final long rollupCaptureTime;
        private final int fromRollupLevel;

        private TransactionRollupResultSetExtractor(long rollupCaptureTime, int fromRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fromRollupLevel = fromRollupLevel;
        }

        @Override
        public List<Aggregate> extractData(ResultSet resultSet) throws Exception {
            Map<String, Map<String, MergedAggregate>> mergedAggregates = Maps.newHashMap();
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                String transactionName = checkNotNull(resultSet.getString(2));
                Map<String, MergedAggregate> mergedAggregateMap =
                        mergedAggregates.get(transactionType);
                if (mergedAggregateMap == null) {
                    mergedAggregateMap = Maps.newHashMap();
                    mergedAggregates.put(transactionType, mergedAggregateMap);
                }
                MergedAggregate mergedAggregate = mergedAggregateMap.get(transactionName);
                if (mergedAggregate == null) {
                    mergedAggregate = new MergedAggregate(rollupCaptureTime, transactionType,
                            transactionName, configService.getAdvancedConfig()
                                    .maxAggregateQueriesPerQueryType());
                    mergedAggregateMap.put(transactionName, mergedAggregate);
                }
                merge(mergedAggregate, resultSet, 3, fromRollupLevel);
            }
            List<Aggregate> aggregates = Lists.newArrayList();
            ScratchBuffer scratchBuffer = new ScratchBuffer();
            for (Map<String, MergedAggregate> mergedAggregateMap : mergedAggregates.values()) {
                for (MergedAggregate mergedAggregate : mergedAggregateMap.values()) {
                    aggregates.add(mergedAggregate.toAggregate(scratchBuffer));
                }
            }
            return aggregates;
        }
    }

    public static class MergedAggregate {

        private long captureTime;
        private final String transactionType;
        private final @Nullable String transactionName;
        private long totalMicros;
        private long errorCount;
        private long transactionCount;
        private @Nullable Long totalCpuMicros;
        private @Nullable Long totalBlockedMicros;
        private @Nullable Long totalWaitedMicros;
        private @Nullable Long totalAllocatedKBytes;
        private final LazyHistogram lazyHistogram = new LazyHistogram();
        private final AggregateTimer syntheticRootTimer = AggregateTimer.createSyntheticRootTimer();
        private final QueryComponent queryComponent;
        private final ProfileNode syntheticProfileNode = ProfileNode.createSyntheticRoot();

        public MergedAggregate(long captureTime, String transactionType,
                @Nullable String transactionName, int maxAggregateQueriesPerQueryType) {
            this.captureTime = captureTime;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            queryComponent = new QueryComponent(maxAggregateQueriesPerQueryType, 0);
        }

        public void setCaptureTime(long captureTime) {
            this.captureTime = captureTime;
        }

        public void addTotalMicros(long totalMicros) {
            this.totalMicros += totalMicros;
        }

        public void addErrorCount(long errorCount) {
            this.errorCount += errorCount;
        }

        public void addTransactionCount(long transactionCount) {
            this.transactionCount += transactionCount;
        }

        public void addTotalCpuMicros(@Nullable Long totalCpuMicros) {
            this.totalCpuMicros = nullAwareAdd(this.totalCpuMicros, totalCpuMicros);
        }

        public void addTotalBlockedMicros(@Nullable Long totalBlockedMicros) {
            this.totalBlockedMicros = nullAwareAdd(this.totalBlockedMicros, totalBlockedMicros);
        }

        public void addTotalWaitedMicros(@Nullable Long totalWaitedMicros) {
            this.totalWaitedMicros = nullAwareAdd(this.totalWaitedMicros, totalWaitedMicros);
        }

        public void addTotalAllocatedKBytes(@Nullable Long totalAllocatedKBytes) {
            this.totalAllocatedKBytes = nullAwareAdd(this.totalAllocatedKBytes,
                    totalAllocatedKBytes);
        }

        public void addHistogram(byte[] histogram) throws DataFormatException {
            lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
        }

        public void addTimers(String timers) throws IOException {
            AggregateTimer syntheticRootTimers = mapper.readValue(timers, AggregateTimer.class);
            this.syntheticRootTimer.mergeMatchedTimer(syntheticRootTimers);
        }

        public Aggregate toAggregate(ScratchBuffer scratchBuffer) throws IOException {
            ByteBuffer buffer =
                    scratchBuffer.getBuffer(lazyHistogram.getNeededByteBufferCapacity());
            buffer.clear();
            byte[] histogram = lazyHistogram.encodeUsingTempByteBuffer(buffer);
            return Aggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(captureTime)
                    .totalMicros(totalMicros)
                    .errorCount(errorCount)
                    .transactionCount(transactionCount)
                    .totalCpuMicros(totalCpuMicros)
                    .totalBlockedMicros(totalBlockedMicros)
                    .totalWaitedMicros(totalWaitedMicros)
                    .totalAllocatedKBytes(totalAllocatedKBytes)
                    .histogram(histogram)
                    .timers(mapper.writeValueAsString(syntheticRootTimer))
                    .queries(getQueriesJson())
                    .profile(getProfileJson())
                    .build();
        }

        private void addQueries(String queryContent) throws IOException {
            queryComponent.mergeQueries(queryContent);
        }

        private void addProfile(String profileContent) throws IOException {
            ProfileNode profileNode =
                    ObjectMappers.readRequiredValue(mapper, profileContent, ProfileNode.class);
            syntheticProfileNode.mergeMatchedNode(profileNode);
        }

        private @Nullable String getQueriesJson() throws IOException {
            Map<String, List<AggregateQuery>> queries =
                    queryComponent.getOrderedAndTruncatedQueries();
            if (queries.isEmpty()) {
                return null;
            }
            return mapper.writeValueAsString(queries);
        }

        private @Nullable String getProfileJson() throws IOException {
            if (syntheticProfileNode.getSampleCount() == 0) {
                return null;
            }
            return mapper.writeValueAsString(syntheticProfileNode);
        }

        private static @Nullable Long nullAwareAdd(@Nullable Long x, @Nullable Long y) {
            if (x == null) {
                return y;
            }
            if (y == null) {
                return x;
            }
            return x + y;
        }
    }
}
