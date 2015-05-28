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
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
import org.glowroot.collector.QueryComponent;
import org.glowroot.collector.QueryComponent.AggregateQueryData;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ObjectMappers;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.transaction.model.ProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common.Checkers.castUntainted;

public class AggregateDao {

    public static final long ROLLUP_THRESHOLD_MILLIS = HOURS.toMillis(1);

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
                    Column.of("trace_count", Types.BIGINT),
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
                    Column.of("trace_count", Types.BIGINT),
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
    private static final ImmutableList<Index> overallAggregateRollupIndexes =
            ImmutableList.<Index>of(Index.of("overall_aggregate_rollup_1_idx",
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
    private static final ImmutableList<Index> transactionAggregateRollupIndexes =
            ImmutableList.<Index>of(Index.of("transaction_aggregate_rollup_1_idx",
                    transactionAggregateIndexColumns));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;
    private final ConfigService configService;

    private final long fixedRollupMillis;

    private volatile long lastRollupTime;

    AggregateDao(DataSource dataSource, CappedDatabase cappedDatabase, ConfigService configService,
            long fixedRollupSeconds) throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
        this.configService = configService;
        fixedRollupMillis = fixedRollupSeconds * 1000;
        dataSource.syncTable("overall_aggregate", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate", overallAggregateIndexes);
        dataSource.syncTable("transaction_aggregate", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate", transactionAggregateIndexes);
        dataSource.syncTable("overall_aggregate_rollup_1", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate_rollup_1", overallAggregateRollupIndexes);
        dataSource.syncTable("transaction_aggregate_rollup_1", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate_rollup_1", transactionAggregateRollupIndexes);
        // TODO initial rollup in case store is not called in a reasonable time
        lastRollupTime = dataSource.queryForLong(
                "select ifnull(max(capture_time), 0) from overall_aggregate_rollup_1");
    }

    void store(final List<Aggregate> overallAggregates, List<Aggregate> transactionAggregates,
            long captureTime) throws Exception {
        store(overallAggregates, transactionAggregates, "");
        long rollupTime =
                (long) Math.floor(captureTime / (double) fixedRollupMillis) * fixedRollupMillis;
        if (rollupTime > lastRollupTime) {
            rollup(lastRollupTime, rollupTime);
            lastRollupTime = rollupTime;
        }
    }

    // captureTimeFrom is non-inclusive
    public TransactionSummary readOverallTransactionSummary(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        TransactionSummary summary = dataSource.query("select sum(total_micros),"
                + " sum(transaction_count) from overall_aggregate where transaction_type = ?"
                + " and capture_time > ? and capture_time <= ?",
                new OverallSummaryResultSetExtractor(), transactionType, captureTimeFrom,
                captureTimeTo);
        if (summary == null) {
            // this can happen if datasource is in the middle of closing
            return TransactionSummary.builder().build();
        } else {
            return summary;
        }
    }

    // captureTimeFrom is non-inclusive
    public QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        ImmutableList<TransactionSummary> summaries = dataSource.query("select transaction_name,"
                + " sum(total_micros), sum(transaction_count) from transaction_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " group by transaction_name order by " + getSortClause(query.sortOrder())
                + ", transaction_name limit ?", new TransactionSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summaries, query.limit());
    }

    // captureTimeFrom is non-inclusive
    public ErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        ErrorSummary result = dataSource.query("select sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", new OverallErrorSummaryResultSetExtractor(),
                transactionType, captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return ErrorSummary.builder().build();
        } else {
            return result;
        }
    }

    // captureTimeFrom is non-inclusive
    public QueryResult<ErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query)
            throws SQLException {
        ImmutableList<ErrorSummary> summary = dataSource.query("select transaction_name,"
                + " sum(error_count), sum(transaction_count) from transaction_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " group by transaction_type, transaction_name having sum(error_count) > 0"
                + " order by " + getSortClause(query.sortOrder())
                + ", transaction_type, transaction_name limit ?", new ErrorSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summary, query.limit());
    }

    public ImmutableList<Aggregate> readOverallAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws SQLException {
        String rollupSuffix = getRollupSuffix(rollupLevel);
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, trace_count, histogram, timers"
                + " from overall_aggregate" + rollupSuffix + " where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new AggregateRowMapper(transactionType, null), transactionType, captureTimeFrom,
                captureTimeTo);
    }

    public ImmutableList<Aggregate> readTransactionAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
            throws SQLException {
        String rollupSuffix = getRollupSuffix(rollupLevel);
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, trace_count, histogram, timers"
                + " from transaction_aggregate" + rollupSuffix + " where transaction_type = ?"
                + " and transaction_name = ? and capture_time >= ? and capture_time <= ?",
                new AggregateRowMapper(transactionType, transactionName), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<CharSource> readOverallQueries(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return readOverallCappedDatabaseRows("queries_capped_id", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<CharSource> readTransactionQueries(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return readTransactionCappedDatabaseRows("queries_capped_id", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<CharSource> readOverallProfiles(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return readOverallCappedDatabaseRows("profile_capped_id", transactionType,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public ImmutableList<CharSource> readTransactionProfiles(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return readTransactionCappedDatabaseRows("profile_capped_id", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<ErrorPoint> readOverallErrorPoints(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws SQLException {
        String rollupSuffix = getRollupSuffix(rollupLevel);
        return dataSource.query("select capture_time, sum(error_count), sum(transaction_count)"
                + " from overall_aggregate" + rollupSuffix + " where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? group by capture_time"
                + " having sum(error_count) > 0 order by capture_time", new ErrorPointRowMapper(),
                transactionType, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
            throws SQLException {
        String rollupSuffix = getRollupSuffix(rollupLevel);
        return dataSource.query("select capture_time, error_count, transaction_count from"
                + " transaction_aggregate" + rollupSuffix + " where transaction_type = ?"
                + " and transaction_name = ? and capture_time >= ? and capture_time <= ?"
                + " and error_count > 0", new ErrorPointRowMapper(), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallTraces(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return shouldHaveOverallSomething("trace_count", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionTraces(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return shouldHaveTransactionSomething("trace_count", transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallErrorTraces(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return shouldHaveOverallSomething("error_count", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionErrorTraces(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return shouldHaveTransactionSomething("error_count", transactionType, transactionName,
                captureTimeFrom, captureTimeTo);
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table overall_aggregate");
        dataSource.execute("truncate table transaction_aggregate");
        dataSource.execute("truncate table overall_aggregate_rollup_1");
        dataSource.execute("truncate table transaction_aggregate_rollup_1");
    }

    void deleteBefore(long captureTime) throws SQLException {
        dataSource.deleteBefore("overall_aggregate", captureTime);
        dataSource.deleteBefore("overall_aggregate_rollup_1", captureTime);
        dataSource.deleteBefore("transaction_aggregate", captureTime);
        dataSource.deleteBefore("transaction_aggregate_rollup_1", captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil(capture_time / " + fixedRollupMillis + ".0) * "
                + fixedRollupMillis);
        List<Long> rollupTimes = dataSource.query("select distinct " + captureTimeSql
                + " from overall_aggregate where capture_time > ? and capture_time <= ?",
                new LongRowMapper(), lastRollupTime, curentRollupTime);
        for (Long rollupTime : rollupTimes) {
            rollupOneInterval(rollupTime);
        }
    }

    private void rollupOneInterval(long rollupTime) throws Exception {
        List<Aggregate> overallAggregates = dataSource.query("select transaction_type,"
                + " total_micros, error_count, transaction_count, total_cpu_micros,"
                + " total_blocked_micros, total_waited_micros, total_allocated_kbytes,"
                + " trace_count, queries_capped_id, profile_capped_id, histogram, timers"
                + " from overall_aggregate where capture_time > ? and capture_time <= ?",
                new OverallRollupResultSetExtractor(rollupTime), rollupTime - fixedRollupMillis,
                rollupTime);
        if (overallAggregates == null) {
            // data source is closing
            return;
        }
        List<Aggregate> transactionAggregates = dataSource.query("select transaction_type,"
                + " transaction_name, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_kbytes, trace_count, queries_capped_id, profile_capped_id,"
                + " histogram, timers from transaction_aggregate where capture_time > ?"
                + " and capture_time <= ?", new TransactionRollupResultSetExtractor(rollupTime),
                rollupTime - fixedRollupMillis, rollupTime);
        if (transactionAggregates == null) {
            // data source is closing
            return;
        }
        store(overallAggregates, transactionAggregates, "_rollup_1");
    }

    private void store(List<Aggregate> overallAggregates, List<Aggregate> transactionAggregates,
            @Untainted String rollupSuffix) throws Exception {
        dataSource.batchUpdate("insert into overall_aggregate" + rollupSuffix
                + " (transaction_type, capture_time, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_kbytes, trace_count, queries_capped_id, profile_capped_id,"
                + " histogram, timers) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallBatchAdder(overallAggregates));
        dataSource.batchUpdate("insert into transaction_aggregate" + rollupSuffix
                + " (transaction_type, transaction_name, capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_kbytes, trace_count, queries_capped_id,"
                + " profile_capped_id, histogram, timers)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionBatchAdder(transactionAggregates));
    }

    // captureTimeFrom is non-inclusive
    private ImmutableList<CharSource> readOverallCappedDatabaseRows(@Untainted String columnName,
            String transactionType, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select " + columnName + " from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ? and "
                + columnName + " >= ?", new CappedDatabaseRowMapper(), transactionType,
                captureTimeFrom, captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    private ImmutableList<CharSource> readTransactionCappedDatabaseRows(
            @Untainted String columnName, String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select " + columnName + " from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and " + columnName + " >= ?",
                new CappedDatabaseRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
    }

    private boolean shouldHaveOverallSomething(@Untainted String countColumnName,
            String transactionType, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " and " + countColumnName + " > 0 limit 1", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    private boolean shouldHaveTransactionSomething(@Untainted String countColumnName,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and  " + countColumnName + " > 0 limit 1",
                transactionType, transactionName, captureTimeFrom, captureTimeTo);
    }

    private void bindCommon(PreparedStatement preparedStatement, Aggregate overallAggregate,
            int startIndex) throws Exception {
        Long queriesCappedId = null;
        String queries = overallAggregate.queries();
        if (queries != null) {
            queriesCappedId = cappedDatabase.write(CharSource.wrap(queries));
        }
        Long profileCappedId = null;
        String profile = overallAggregate.profile();
        if (profile != null) {
            profileCappedId = cappedDatabase.write(CharSource.wrap(profile));
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
        preparedStatement.setLong(i++, overallAggregate.traceCount());
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

    private static @Untainted String getRollupSuffix(int rollupLevel) {
        if (rollupLevel == 0) {
            return "";
        } else {
            return "_rollup_1";
        }
    }

    @Value.Immutable
    @JsonSerialize
    public abstract static class TransactionSummaryQueryBase {
        public abstract String transactionType();
        public abstract long from();
        public abstract long to();
        public abstract TransactionSummarySortOrder sortOrder();
        public abstract int limit();
    }

    @Value.Immutable
    @JsonSerialize
    public abstract static class ErrorSummaryQueryBase {
        public abstract String transactionType();
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
        private OverallBatchAdder(List<Aggregate> overallAggregates) {
            this.overallAggregates = overallAggregates;
        }
        @Override
        public void addBatches(PreparedStatement preparedStatement) throws Exception {
            for (Aggregate overallAggregate : overallAggregates) {
                preparedStatement.setString(1, overallAggregate.transactionType());
                bindCommon(preparedStatement, overallAggregate, 2);
                preparedStatement.addBatch();
            }
        }
    }

    private class TransactionBatchAdder implements BatchAdder {
        private final List<Aggregate> transactionAggregates;
        private TransactionBatchAdder(List<Aggregate> transactionAggregates) {
            this.transactionAggregates = transactionAggregates;
        }
        @Override
        public void addBatches(PreparedStatement preparedStatement) throws Exception {
            for (Aggregate transactionAggregate : transactionAggregates) {
                preparedStatement.setString(1, transactionAggregate.transactionType());
                preparedStatement.setString(2, transactionAggregate.transactionName());
                bindCommon(preparedStatement, transactionAggregate, 3);
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
                    .traceCount(resultSet.getLong(i++))
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

    private class CappedDatabaseRowMapper implements RowMapper<CharSource> {
        @Override
        public CharSource mapRow(ResultSet resultSet) throws SQLException {
            return cappedDatabase.read(resultSet.getLong(1), OVERWRITTEN);
        }
    }

    private static class LongRowMapper implements RowMapper<Long> {
        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    private abstract class RollupResultSetExtractor implements ResultSetExtractor<List<Aggregate>> {
        void merge(MergedAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex)
                throws Exception {
            int i = startColumnIndex;
            long totalMicros = resultSet.getLong(i++);
            long errorCount = resultSet.getLong(i++);
            long transactionCount = resultSet.getLong(i++);
            Long totalCpuMicros = resultSet.getLong(i++);
            Long totalBlockedMicros = RowMappers.getLong(resultSet, i++);
            Long totalWaitedMicros = RowMappers.getLong(resultSet, i++);
            Long totalAllocatedKBytes = RowMappers.getLong(resultSet, i++);
            long traceCount = resultSet.getLong(i++);
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
            mergedAggregate.addTraceCount(traceCount);
            mergedAggregate.addHistogram(histogram);
            mergedAggregate.addTimers(timers);
            if (queriesCappedId != null) {
                String queriesContent =
                        cappedDatabase.read(queriesCappedId, AggregateDao.OVERWRITTEN).read();
                if (!queriesContent.equals(AggregateDao.OVERWRITTEN)) {
                    mergedAggregate.addQueries(queriesContent);
                }
            }
            if (profileCappedId != null) {
                String profileContent =
                        cappedDatabase.read(profileCappedId, AggregateDao.OVERWRITTEN).read();
                if (!profileContent.equals(AggregateDao.OVERWRITTEN)) {
                    mergedAggregate.addProfile(profileContent);
                }
            }
        }
    }

    private class OverallRollupResultSetExtractor extends RollupResultSetExtractor {
        private final long rollupCaptureTime;
        private OverallRollupResultSetExtractor(long rollupCaptureTime) {
            this.rollupCaptureTime = rollupCaptureTime;
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
                merge(mergedAggregate, resultSet, 2);
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
        private TransactionRollupResultSetExtractor(long rollupCaptureTime) {
            this.rollupCaptureTime = rollupCaptureTime;
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
                merge(mergedAggregate, resultSet, 3);
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
        private long traceCount;
        private final LazyHistogram lazyHistogram = new LazyHistogram();
        private final AggregateTimer syntheticRootTimer = AggregateTimer.createSyntheticRootTimer();
        private final QueryComponent queryComponent;
        private final ProfileNode syntheticProfileNode = ProfileNode.createSyntheticRoot();
        // do not use static ObjectMapper here, see comment for QueryComponent.mergedQueries()
        private final ObjectMapper tempMapper = ObjectMappers.create();

        public MergedAggregate(long captureTime, String transactionType,
                @Nullable String transactionName, int maxAggregateQueriesPerQueryType) {
            this.captureTime = captureTime;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            // MAX_VALUE because when merging apply limit at the end
            queryComponent = new QueryComponent(maxAggregateQueriesPerQueryType, true);
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

        public void addTraceCount(long traceCount) {
            this.traceCount += traceCount;
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
                    .traceCount(traceCount)
                    .histogram(histogram)
                    .timers(mapper.writeValueAsString(syntheticRootTimer))
                    .queries(getQueriesJson())
                    .profile(getProfileJson())
                    .build();
        }

        private void addQueries(String queryContent) throws IOException {
            queryComponent.mergeQueries(queryContent, tempMapper);
        }

        private void addProfile(String profileContent) throws IOException {
            ProfileNode profileNode =
                    ObjectMappers.readRequiredValue(mapper, profileContent, ProfileNode.class);
            syntheticProfileNode.mergeMatchedNode(profileNode);
        }

        private @Nullable String getQueriesJson() throws IOException {
            Map<String, Map<String, AggregateQueryData>> queries = queryComponent
                    .getMergedQueries();
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
