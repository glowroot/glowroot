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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.ImmutableAggregate;
import org.glowroot.config.MarshalingRoutines;
import org.glowroot.config.MarshalingRoutines.LowercaseMarshaling;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;

public class AggregateDao implements AggregateRepository {

    public static final String OVERWRITTEN = "{\"overwritten\":true}";

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    // capture time rounded up to nearest 5 min
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT), // microseconds
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("profile_sample_count", Types.BIGINT),
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    ImmutableColumn.of("metrics", Types.VARCHAR), // json data
                    ImmutableColumn.of("histogram", Types.BLOB));

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    ImmutableColumn.of("transaction_name", Types.VARCHAR),
                    // capture time rounded up to nearest 5 min
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT), // microseconds
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("profile_sample_count", Types.BIGINT),
                    ImmutableColumn.of("profile_capped_id", Types.BIGINT), // capped database id
                    ImmutableColumn.of("metrics", Types.VARCHAR), // json data
                    ImmutableColumn.of("histogram", Types.BLOB));

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> overallAggregateIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("overall_aggregate_idx",
                    ImmutableList.of("capture_time", "transaction_type", "total_micros",
                            "transaction_count", "error_count")));

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<Index> transactionAggregateIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("transaction_aggregate_idx",
                    ImmutableList.of("capture_time", "transaction_type", "transaction_name",
                            "total_micros", "transaction_count", "error_count")));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;

    AggregateDao(DataSource dataSource, CappedDatabase cappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
        dataSource.syncTable("overall_aggregate", overallAggregatePointColumns);
        dataSource.syncIndexes("overall_aggregate", overallAggregateIndexes);
        dataSource.syncTable("transaction_aggregate", transactionAggregateColumns);
        dataSource.syncIndexes("transaction_aggregate", transactionAggregateIndexes);
    }

    @Override
    public void store(final List<Aggregate> overallAggregates,
            List<Aggregate> transactionAggregates) throws Exception {
        dataSource.batchUpdate("insert into overall_aggregate (transaction_type, capture_time,"
                + " total_micros, error_count, transaction_count, profile_sample_count,"
                + " profile_capped_id, metrics, histogram) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new OverallBatchAdder(overallAggregates));
        dataSource.batchUpdate("insert into transaction_aggregate (transaction_type,"
                + " transaction_name, capture_time, total_micros, error_count,"
                + " transaction_count, profile_sample_count, profile_capped_id, metrics,"
                + " histogram) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TransactionBatchAdder(transactionAggregates));
    }

    public TransactionSummary readOverallTransactionSummary(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        TransactionSummary summary = dataSource.query("select sum(total_micros),"
                + " sum(transaction_count) from overall_aggregate where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ?",
                new OverallSummaryResultSetExtractor(), transactionType,
                captureTimeFrom,
                captureTimeTo);
        if (summary == null) {
            // this can happen if datasource is in the middle of closing
            return ImmutableTransactionSummary.builder().build();
        } else {
            return summary;
        }
    }

    public QueryResult<TransactionSummary> readTransactionSummaries(
            TransactionSummaryQuery query) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        ImmutableList<TransactionSummary> summaries = dataSource.query("select transaction_name,"
                + " sum(total_micros), sum(transaction_count) from transaction_aggregate"
                + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                + " group by transaction_name order by " + getSortClause(query.sortOrder())
                + ", transaction_name limit ?",
                new TransactionSummaryRowMapper(), query.transactionType(), query.from(),
                query.to(), query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summaries, query.limit());
    }

    public ErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        ErrorSummary result = dataSource.query("select sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where transaction_type = ? and capture_time >= ?"
                + " and capture_time <= ?", new OverallErrorSummaryResultSetExtractor(),
                transactionType, captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return ImmutableErrorSummary.builder().build();
        } else {
            return result;
        }
    }

    public QueryResult<ErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query)
            throws SQLException {
        ImmutableList<ErrorSummary> summary = dataSource.query("select transaction_name,"
                + " sum(error_count), sum(transaction_count) from transaction_aggregate"
                + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                + " group by transaction_type, transaction_name having sum(error_count) > 0"
                + " order by " + getSortClause(query.sortOrder()) + " , transaction_type,"
                + " transaction_name limit ?", new ErrorSummaryRowMapper(),
                query.transactionType(), query.from(), query.to(),
                query.limit() + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(summary, query.limit());
    }

    public ImmutableList<Aggregate> readOverallAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, profile_sample_count, profile_capped_id, metrics, histogram"
                + " from overall_aggregate where transaction_type = ? and capture_time >= ? and"
                + " capture_time <= ? order by capture_time",
                new AggregateRowMapper(transactionType, null), transactionType, captureTimeFrom,
                captureTimeTo);
    }

    public ImmutableList<Aggregate> readTransactionAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, profile_sample_count, profile_capped_id, metrics, histogram"
                + " from transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time >= ? and capture_time <= ?",
                new AggregateRowMapper(transactionType, transactionName), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<CharSource> readOverallProfiles(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select profile_capped_id from overall_aggregate where"
                + " transaction_type = ? and capture_time >= ? and capture_time <= ? and"
                + " profile_capped_id >= ?", new CappedDatabaseRowMapper(), transactionType,
                captureTimeFrom, captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
    }

    public ImmutableList<CharSource> readTransactionProfiles(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select profile_capped_id from transaction_aggregate where"
                + " transaction_type = ? and transaction_name = ? and capture_time >= ?"
                + " and capture_time <= ? and profile_capped_id >= ?",
                new CappedDatabaseRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo, cappedDatabase.getSmallestNonExpiredId());
    }

    public ImmutableList<ErrorPoint> readOverallErrorPoints(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where transaction_type = ? and capture_time >= ?"
                + " and capture_time <= ? group by capture_time having sum(error_count) > 0"
                + " order by capture_time", new ErrorPointRowMapper(), transactionType,
                captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, error_count, transaction_count from"
                + " transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time >= ? and capture_time <= ? and error_count > 0",
                new ErrorPointRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    public long readOverallProfileSampleCount(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForLong("select sum(profile_sample_count) from overall_aggregate"
                + " where transaction_type = ? and capture_time >= ? and capture_time <= ?"
                + " and profile_capped_id >= ?", transactionType, captureTimeFrom, captureTimeTo,
                cappedDatabase.getSmallestNonExpiredId());
    }

    public long readTransactionProfileSampleCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForLong("select sum(profile_sample_count) from"
                + " transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time >= ? and capture_time <= ? and profile_capped_id >= ?",
                transactionType, transactionName, captureTimeFrom, captureTimeTo,
                cappedDatabase.getSmallestNonExpiredId());
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table overall_aggregate");
        dataSource.execute("truncate table transaction_aggregate");
    }

    void deleteBefore(long captureTime) throws SQLException {
        // delete 100 at a time, which is both faster than deleting all at once, and doesn't
        // lock the single jdbc connection for one large chunk of time
        while (true) {
            int deleted = dataSource.update(
                    "delete from overall_aggregate where capture_time < ?", captureTime);
            deleted += dataSource.update(
                    "delete from transaction_aggregate where capture_time < ?", captureTime);
            if (deleted == 0) {
                break;
            }
        }
    }

    private @Untainted String getSortClause(TransactionSummarySortOrder sortOrder) {
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

    @Value.Immutable
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    public abstract static class TransactionSummaryQuery {
        abstract String transactionType();
        abstract long from();
        abstract long to();
        abstract TransactionSummarySortOrder sortOrder();
        abstract int limit();
    }

    @Value.Immutable
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    public abstract static class ErrorSummaryQuery {
        abstract String transactionType();
        abstract long from();
        abstract long to();
        abstract ErrorSummarySortOrder sortOrder();
        abstract int limit();
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
                preparedStatement.setString(1, overallAggregate.transactionType());
                preparedStatement.setLong(2, overallAggregate.captureTime());
                preparedStatement.setLong(3, overallAggregate.totalMicros());
                preparedStatement.setLong(4, overallAggregate.errorCount());
                preparedStatement.setLong(5, overallAggregate.transactionCount());
                preparedStatement.setLong(6, overallAggregate.profileSampleCount());
                if (profileId == null) {
                    preparedStatement.setNull(7, Types.BIGINT);
                } else {
                    preparedStatement.setLong(7, profileId);
                }
                preparedStatement.setString(8, overallAggregate.metrics());
                preparedStatement.setBytes(9, overallAggregate.histogram());
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
                preparedStatement.setString(1, transactionAggregate.transactionType());
                preparedStatement.setString(2, transactionAggregate.transactionName());
                preparedStatement.setLong(3, transactionAggregate.captureTime());
                preparedStatement.setLong(4, transactionAggregate.totalMicros());
                preparedStatement.setLong(5, transactionAggregate.errorCount());
                preparedStatement.setLong(6, transactionAggregate.transactionCount());
                preparedStatement.setLong(7, transactionAggregate.profileSampleCount());
                if (profileId == null) {
                    preparedStatement.setNull(8, Types.BIGINT);
                } else {
                    preparedStatement.setLong(8, profileId);
                }
                preparedStatement.setString(9, transactionAggregate.metrics());
                preparedStatement.setBytes(10, transactionAggregate.histogram());
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
            return ImmutableAggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(resultSet.getLong(1))
                    .totalMicros(resultSet.getLong(2))
                    .errorCount(resultSet.getLong(3))
                    .transactionCount(resultSet.getLong(4))
                    .metrics(checkNotNull(resultSet.getString(7))) // should never be null
                    .histogram(checkNotNull(resultSet.getBytes(8))) // should never be null
                    .profileSampleCount(resultSet.getLong(5))
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
}
