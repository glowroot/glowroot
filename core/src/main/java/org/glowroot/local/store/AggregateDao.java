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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.ErrorPoint;
import org.glowroot.collector.ErrorSummary;
import org.glowroot.collector.ImmutableAggregate;
import org.glowroot.collector.ImmutableErrorPoint;
import org.glowroot.collector.ImmutableErrorSummary;
import org.glowroot.collector.ImmutableTransactionSummary;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.config.MarshalingRoutines;
import org.glowroot.config.MarshalingRoutines.LowercaseMarshaling;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    public static final long ROLLUP_THRESHOLD_MILLIS = HOURS.toMillis(1);

    public static final String OVERWRITTEN = "{\"overwritten\":true}";

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_micros", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_micros", Types.BIGINT),
                    ImmutableColumn.of("total_waited_micros", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", Types.BIGINT),
                    ImmutableColumn.of("profile_sample_count", Types.BIGINT),
                    ImmutableColumn.of("trace_count", Types.BIGINT),
                    // profile json is always from "synthetic root"
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    // metrics json is always from "synthetic root"
                    ImmutableColumn.of("metrics", Types.VARCHAR),
                    ImmutableColumn.of("histogram", Types.BLOB));

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("transaction_name", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT),
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("total_cpu_micros", Types.BIGINT),
                    ImmutableColumn.of("total_blocked_micros", Types.BIGINT),
                    ImmutableColumn.of("total_waited_micros", Types.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", Types.BIGINT),
                    ImmutableColumn.of("profile_sample_count", Types.BIGINT),
                    ImmutableColumn.of("trace_count", Types.BIGINT),
                    // profile json is always from "synthetic root"
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    // metrics json is always from "synthetic root"
                    ImmutableColumn.of("metrics", Types.VARCHAR), // json data
                    ImmutableColumn.of("histogram", Types.BLOB));

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "total_micros",
                    "transaction_count", "error_count");
    private static final ImmutableList<Index> overallAggregateIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("overall_aggregate_idx", overallAggregateIndexColumns));
    private static final ImmutableList<Index> overallAggregateRollupIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("overall_aggregate_rollup_1_idx",
                    overallAggregateIndexColumns));

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name",
                    "total_micros", "transaction_count", "error_count");
    private static final ImmutableList<Index> transactionAggregateIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("transaction_aggregate_idx",
                    transactionAggregateIndexColumns));
    private static final ImmutableList<Index> transactionAggregateRollupIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("transaction_aggregate_rollup_1_idx",
                    transactionAggregateIndexColumns));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;

    private final long fixedRollupMillis;

    private volatile long lastRollupTime;

    AggregateDao(DataSource dataSource, CappedDatabase cappedDatabase, long fixedRollupSeconds)
            throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
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

    @Override
    public void store(final List<Aggregate> overallAggregates,
            List<Aggregate> transactionAggregates, long captureTime) throws Exception {
        store(overallAggregates, transactionAggregates, "");
        long rollupTime =
                (long) Math.floor(captureTime / (double) fixedRollupMillis) * fixedRollupMillis;
        if (rollupTime > lastRollupTime) {
            rollup(lastRollupTime, rollupTime);
            lastRollupTime = rollupTime;
        }
    }

    // captureTimeFrom is non-inclusive
    // TODO optimize by going against rollup table for majority of data
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
            return ImmutableTransactionSummary.builder().build();
        } else {
            return summary;
        }
    }

    // captureTimeFrom is non-inclusive
    // TODO optimize by going against rollup table for majority of data
    public QueryResult<TransactionSummary> readTransactionSummaries(
            TransactionSummaryQuery query) throws SQLException {
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
    // TODO optimize by going against rollup table for majority of data
    public ErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        ErrorSummary result = dataSource.query("select sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where transaction_type = ? and capture_time > ?"
                + " and capture_time <= ?", new OverallErrorSummaryResultSetExtractor(),
                transactionType, captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return ImmutableErrorSummary.builder().build();
        } else {
            return result;
        }
    }

    // captureTimeFrom is non-inclusive
    // TODO optimize by going against rollup table for majority of data
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
                + " total_waited_micros, total_allocated_bytes, profile_sample_count, trace_count,"
                + " metrics, histogram from overall_aggregate" + rollupSuffix
                + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new AggregateRowMapper(transactionType, null),
                transactionType, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<Aggregate> readTransactionAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
            throws SQLException {
        String rollupSuffix = getRollupSuffix(rollupLevel);
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_bytes, profile_sample_count, trace_count,"
                + " metrics, histogram from transaction_aggregate" + rollupSuffix
                + " where transaction_type = ? and transaction_name = ? and capture_time >= ?"
                + " and capture_time <= ?",
                new AggregateRowMapper(transactionType, transactionName), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    // TODO optimize by going against rollup table for majority of data
    public ImmutableList<CharSource> readOverallProfiles(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select profile_capped_id from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ? and"
                + " profile_capped_id >= ?", new CappedDatabaseRowMapper(), transactionType,
                captureTimeFrom, captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    // TODO optimize by going against rollup table for majority of data
    public ImmutableList<CharSource> readTransactionProfiles(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select profile_capped_id from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and profile_capped_id >= ?",
                new CappedDatabaseRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
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
    public long readOverallProfileSampleCount(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        // TODO optimize by going against rollup table for majority of data
        return dataSource.queryForLong("select sum(profile_sample_count) from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " and profile_capped_id >= ?", transactionType, captureTimeFrom, captureTimeTo,
                cappedDatabase.getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    public long readTransactionProfileSampleCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        // TODO optimize by going against rollup table for majority of data
        return dataSource.queryForLong("select sum(profile_sample_count) from"
                + " transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time > ? and capture_time <= ? and profile_capped_id >= ?",
                transactionType, transactionName, captureTimeFrom, captureTimeTo,
                cappedDatabase.getSmallestNonExpiredId());
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallProfiles(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " and profile_sample_count > 0 limit 1", transactionType, captureTimeFrom,
                captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionProfiles(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and profile_sample_count > 0 limit 1", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallTraces(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " and trace_count > 0 limit 1", transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionTraces(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and trace_count > 0 limit 1", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveOverallErrorTraces(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from overall_aggregate"
                + " where transaction_type = ? and capture_time > ? and capture_time <= ?"
                + " and error_count > 0 limit 1", transactionType, captureTimeFrom, captureTimeTo);
    }

    // captureTimeFrom is non-inclusive
    public boolean shouldHaveTransactionErrorTraces(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForExists("select 1 from transaction_aggregate"
                + " where transaction_type = ? and transaction_name = ? and capture_time > ?"
                + " and capture_time <= ? and error_count > 0 limit 1", transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table overall_aggregate");
        dataSource.execute("truncate table transaction_aggregate");
        dataSource.execute("truncate table overall_aggregate_rollup_1");
        dataSource.execute("truncate table transaction_aggregate_rollup_1");
    }

    long getLastRollupTime() throws SQLException {
        return dataSource.queryForLong("select max(capture_time) from overall_aggregate_rollup_1");
    }

    void deleteBefore(long captureTime) throws SQLException {
        deleteBefore("overall_aggregate", captureTime);
        deleteBefore("overall_aggregate_rollup_1", captureTime);
        deleteBefore("transaction_aggregate", captureTime);
        deleteBefore("transaction_aggregate_rollup_1", captureTime);
    }

    private void deleteBefore(@Untainted String tableName, long captureTime) throws SQLException {
        // delete 100 at a time, which is both faster than deleting all at once, and doesn't
        // lock the single jdbc connection for one large chunk of time
        int deleted;
        do {
            deleted = dataSource.update("delete from " + tableName
                    + " where capture_time < ? limit 100", captureTime);
        } while (deleted != 0);
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
                + " total_blocked_micros, total_waited_micros, total_allocated_bytes,"
                + " profile_sample_count, trace_count, profile_capped_id, metrics, histogram"
                + " from overall_aggregate where capture_time > ? and capture_time <= ?",
                new OverallRollupResultSetExtractor(rollupTime), rollupTime - fixedRollupMillis,
                rollupTime);
        List<Aggregate> transactionAggregates = dataSource.query("select transaction_type,"
                + " transaction_name, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_bytes, profile_sample_count, trace_count, profile_capped_id,"
                + " metrics, histogram from transaction_aggregate where capture_time > ?"
                + " and capture_time <= ?", new TransactionRollupResultSetExtractor(rollupTime),
                rollupTime - fixedRollupMillis, rollupTime);
        store(overallAggregates, transactionAggregates, "_rollup_1");
    }

    private void store(final List<Aggregate> overallAggregates,
            List<Aggregate> transactionAggregates, @Untainted String rollupSuffix)
            throws Exception {
        dataSource.batchUpdate("insert into overall_aggregate" + rollupSuffix
                + " (transaction_type, capture_time, total_micros, error_count, transaction_count,"
                + " total_cpu_micros, total_blocked_micros, total_waited_micros,"
                + " total_allocated_bytes, profile_sample_count, trace_count, profile_capped_id,"
                + " metrics, histogram) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallBatchAdder(overallAggregates));
        dataSource.batchUpdate("insert into transaction_aggregate" + rollupSuffix
                + " (transaction_type, transaction_name, capture_time, total_micros, error_count,"
                + " transaction_count, total_cpu_micros, total_blocked_micros,"
                + " total_waited_micros, total_allocated_bytes, profile_sample_count, trace_count,"
                + " profile_capped_id, metrics, histogram) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionBatchAdder(transactionAggregates));
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
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    public abstract static class TransactionSummaryQuery {
        public abstract String transactionType();
        public abstract long from();
        public abstract long to();
        public abstract TransactionSummarySortOrder sortOrder();
        public abstract int limit();
    }

    @Value.Immutable
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    public abstract static class ErrorSummaryQuery {
        public abstract String transactionType();
        public abstract long from();
        public abstract long to();
        public abstract ErrorSummarySortOrder sortOrder();
        public abstract int limit();
    }

    public static enum TransactionSummarySortOrder implements LowercaseMarshaling {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public static enum ErrorSummarySortOrder implements LowercaseMarshaling {
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
                Long profileId = null;
                String profile = overallAggregate.profile();
                if (profile != null) {
                    profileId = cappedDatabase.write(CharSource.wrap(profile));
                }
                int i = 1;
                preparedStatement.setString(i++, overallAggregate.transactionType());
                preparedStatement.setLong(i++, overallAggregate.captureTime());
                preparedStatement.setLong(i++, overallAggregate.totalMicros());
                preparedStatement.setLong(i++, overallAggregate.errorCount());
                preparedStatement.setLong(i++, overallAggregate.transactionCount());
                RowMappers.setLong(preparedStatement, i++, overallAggregate.totalCpuMicros());
                RowMappers.setLong(preparedStatement, i++, overallAggregate.totalBlockedMicros());
                RowMappers.setLong(preparedStatement, i++, overallAggregate.totalWaitedMicros());
                RowMappers.setLong(preparedStatement, i++, overallAggregate.totalAllocatedBytes());
                preparedStatement.setLong(i++, overallAggregate.profileSampleCount());
                preparedStatement.setLong(i++, overallAggregate.traceCount());
                RowMappers.setLong(preparedStatement, i++, profileId);
                preparedStatement.setString(i++, overallAggregate.metrics());
                preparedStatement.setBytes(i++, overallAggregate.histogram());
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
                Long profileId = null;
                String profile = transactionAggregate.profile();
                if (profile != null) {
                    profileId = cappedDatabase.write(CharSource.wrap(profile));
                }
                int i = 1;
                preparedStatement.setString(i++, transactionAggregate.transactionType());
                preparedStatement.setString(i++, transactionAggregate.transactionName());
                preparedStatement.setLong(i++, transactionAggregate.captureTime());
                preparedStatement.setLong(i++, transactionAggregate.totalMicros());
                preparedStatement.setLong(i++, transactionAggregate.errorCount());
                preparedStatement.setLong(i++, transactionAggregate.transactionCount());
                RowMappers.setLong(preparedStatement, i++, transactionAggregate.totalCpuMicros());
                RowMappers.setLong(preparedStatement, i++,
                        transactionAggregate.totalBlockedMicros());
                RowMappers.setLong(preparedStatement, i++,
                        transactionAggregate.totalWaitedMicros());
                RowMappers.setLong(preparedStatement, i++,
                        transactionAggregate.totalAllocatedBytes());
                preparedStatement.setLong(i++, transactionAggregate.profileSampleCount());
                preparedStatement.setLong(i++, transactionAggregate.traceCount());
                RowMappers.setLong(preparedStatement, i++, profileId);
                preparedStatement.setString(i++, transactionAggregate.metrics());
                preparedStatement.setBytes(i++, transactionAggregate.histogram());
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
            return ImmutableTransactionSummary.builder()
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

    private static class OverallErrorSummaryResultSetExtractor implements
            ResultSetExtractor<ErrorSummary> {
        @Override
        public ErrorSummary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableErrorSummary.builder()
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
            return ImmutableErrorSummary.builder()
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
            return ImmutableAggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(resultSet.getLong(i++))
                    .totalMicros(resultSet.getLong(i++))
                    .errorCount(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .totalCpuMicros(resultSet.getLong(i++))
                    .totalBlockedMicros(resultSet.getLong(i++))
                    .totalWaitedMicros(resultSet.getLong(i++))
                    .totalAllocatedBytes(resultSet.getLong(i++))
                    .profileSampleCount(resultSet.getLong(i++))
                    .traceCount(resultSet.getLong(i++))
                    .metrics(checkNotNull(resultSet.getString(i++)))
                    .histogram(checkNotNull(resultSet.getBytes(i++)))
                    .build();
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
            Long totalAllocatedBytes = RowMappers.getLong(resultSet, i++);
            long profileSampleCount = resultSet.getLong(i++);
            long traceCount = resultSet.getLong(i++);
            Long profileCappedId = RowMappers.getLong(resultSet, i++);
            String metrics = checkNotNull(resultSet.getString(i++));
            byte[] histogram = checkNotNull(resultSet.getBytes(i++));

            mergedAggregate.addTotalMicros(totalMicros);
            mergedAggregate.addErrorCount(errorCount);
            mergedAggregate.addTransactionCount(transactionCount);
            mergedAggregate.addTotalCpuMicros(totalCpuMicros);
            mergedAggregate.addTotalBlockedMicros(totalBlockedMicros);
            mergedAggregate.addTotalWaitedMicros(totalWaitedMicros);
            mergedAggregate.addTotalAllocatedBytes(totalAllocatedBytes);
            mergedAggregate.addProfileSampleCount(profileSampleCount);
            mergedAggregate.addTraceCount(traceCount);
            mergedAggregate.addMetrics(metrics);
            mergedAggregate.addHistogram(histogram);
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
                    mergedAggregate = new MergedAggregate(rollupCaptureTime, transactionType, null);
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
                            transactionName);
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
        private @Nullable Long totalAllocatedBytes;
        private long profileSampleCount;
        private long traceCount;
        private final AggregateMetric syntheticRootMetric =
                AggregateMetric.createSyntheticRootMetric();
        private final LazyHistogram lazyHistogram = new LazyHistogram();
        private final AggregateProfileNode syntheticProfileNode =
                AggregateProfileNode.createSyntheticRootNode();

        public MergedAggregate(long captureTime, String transactionType,
                @Nullable String transactionName) {
            this.captureTime = captureTime;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
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

        public void addTotalAllocatedBytes(@Nullable Long totalAllocatedBytes) {
            this.totalAllocatedBytes = nullAwareAdd(this.totalAllocatedBytes, totalAllocatedBytes);
        }

        public void addProfileSampleCount(long profileSampleCount) {
            this.profileSampleCount += profileSampleCount;
        }

        public void addTraceCount(long traceCount) {
            this.traceCount += traceCount;
        }

        public void addMetrics(String metrics) throws IOException {
            AggregateMetric syntheticRootMetric = mapper.readValue(metrics, AggregateMetric.class);
            this.syntheticRootMetric.mergeMatchedMetric(syntheticRootMetric);
        }

        public void addHistogram(byte[] histogram) throws DataFormatException {
            lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
        }

        public void addProfile(String profileContent) throws IOException {
            AggregateProfileNode profileNode = ObjectMappers.readRequiredValue(
                    mapper, profileContent, AggregateProfileNode.class);
            syntheticProfileNode.mergeMatchedNode(profileNode);
        }

        public Aggregate toAggregate(ScratchBuffer scratchBuffer) throws IOException {
            ByteBuffer buffer =
                    scratchBuffer.getBuffer(lazyHistogram.getNeededByteBufferCapacity());
            buffer.clear();
            lazyHistogram.encodeIntoByteBuffer(buffer);
            int size = buffer.position();
            buffer.flip();
            byte[] histogram = new byte[size];
            buffer.get(histogram, 0, size);

            return ImmutableAggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(captureTime)
                    .totalMicros(totalMicros)
                    .errorCount(errorCount)
                    .transactionCount(transactionCount)
                    .totalCpuMicros(totalCpuMicros)
                    .totalBlockedMicros(totalBlockedMicros)
                    .totalWaitedMicros(totalWaitedMicros)
                    .totalAllocatedBytes(totalAllocatedBytes)
                    .profileSampleCount(profileSampleCount)
                    .traceCount(traceCount)
                    .metrics(getMetricsJson())
                    .histogram(histogram)
                    .profile(getProfileJson())
                    .build();
        }

        private String getMetricsJson() throws IOException {
            StringBuilder metrics = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(metrics));
            jg.writeObject(syntheticRootMetric);
            jg.close();
            return metrics.toString();
        }

        private @Nullable String getProfileJson() throws IOException {
            if (syntheticProfileNode.getSampleCount() == 0) {
                return null;
            }
            StringBuilder profile = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(profile));
            jg.writeObject(syntheticProfileNode);
            jg.close();
            return profile.toString();
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
