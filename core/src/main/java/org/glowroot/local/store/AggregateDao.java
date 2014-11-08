/*
 * Copyright 2013-2014 the original author or authors.
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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.Existence;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;

public class AggregateDao implements AggregateRepository {

    private static final Logger logger = LoggerFactory.getLogger(AggregateDao.class);

    private static final ImmutableList<Column> overallAggregatePointColumns = ImmutableList.of(
            new Column("transaction_type", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_micros", Types.BIGINT), // microseconds
            new Column("error_count", Types.BIGINT),
            new Column("transaction_count", Types.BIGINT),
            new Column("metrics", Types.VARCHAR)); // json data

    private static final ImmutableList<Column> transactionAggregateColumns = ImmutableList.of(
            new Column("transaction_type", Types.VARCHAR),
            new Column("transaction_name", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_micros", Types.BIGINT), // microseconds
            new Column("error_count", Types.BIGINT),
            new Column("transaction_count", Types.BIGINT),
            new Column("profile_sample_count", Types.BIGINT),
            new Column("profile_id", Types.VARCHAR),  // capped database id
            new Column("metrics", Types.VARCHAR)); // json data

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> overallAggregateIndexes = ImmutableList.of(
            new Index("overall_aggregate_idx", ImmutableList.of("capture_time", "transaction_type",
                    "total_micros", "transaction_count", "error_count")));

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<Index> transactionAggregateIndexes = ImmutableList.of(
            new Index("transaction_aggregate_idx", ImmutableList.of("capture_time",
                    "transaction_type", "transaction_name", "total_micros", "transaction_count",
                    "error_count")));

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
            final List<Aggregate> transactionAggregates) {
        try {
            dataSource.batchUpdate("insert into overall_aggregate (transaction_type, capture_time,"
                    + " total_micros, error_count, transaction_count, metrics) values (?, ?, ?, ?,"
                    + " ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Aggregate overallAggregate : overallAggregates) {
                        preparedStatement.setString(1,
                                overallAggregate.getTransactionType());
                        preparedStatement.setLong(2, overallAggregate.getCaptureTime());
                        preparedStatement.setLong(3, overallAggregate.getTotalMicros());
                        preparedStatement.setLong(4, overallAggregate.getErrorCount());
                        preparedStatement.setLong(5, overallAggregate.getTransactionCount());
                        preparedStatement.setString(6, overallAggregate.getMetrics());
                        preparedStatement.addBatch();
                    }

                }
            });
            dataSource.batchUpdate("insert into transaction_aggregate (transaction_type,"
                    + " transaction_name, capture_time, total_micros, error_count,"
                    + " transaction_count, profile_sample_count, profile_id, metrics) values (?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Aggregate transactionAggregate : transactionAggregates) {
                        String profileId = null;
                        String profile = transactionAggregate.getProfile();
                        if (profile != null) {
                            profileId = cappedDatabase.write(CharSource.wrap(profile)).getId();
                        }
                        preparedStatement.setString(1,
                                transactionAggregate.getTransactionType());
                        preparedStatement.setString(2,
                                transactionAggregate.getTransactionName());
                        preparedStatement.setLong(3, transactionAggregate.getCaptureTime());
                        preparedStatement.setLong(4, transactionAggregate.getTotalMicros());
                        preparedStatement.setLong(5, transactionAggregate.getErrorCount());
                        preparedStatement.setLong(6, transactionAggregate.getTransactionCount());
                        preparedStatement.setLong(7,
                                transactionAggregate.getProfileSampleCount());
                        preparedStatement.setString(8, profileId);
                        preparedStatement.setString(9, transactionAggregate.getMetrics());
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public ImmutableList<Aggregate> readOverallAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, null, null, metrics from overall_aggregate where"
                + " transaction_type = ? and capture_time >= ? and capture_time <= ? order by"
                + " capture_time", new AggregateRowMapper(transactionType, null),
                transactionType, captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<Aggregate> readTransactionAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        // TODO this query is a little slow because of pulling in metrics for each row
        // and sticking metrics into index does not seem to help
        return dataSource.query("select capture_time, total_micros, error_count,"
                + " transaction_count, profile_sample_count, profile_id, metrics from"
                + " transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time >= ? and capture_time <= ?",
                new AggregateRowMapper(transactionType, transactionName), transactionType,
                transactionName, captureTimeFrom, captureTimeTo);
    }

    public Summary readOverallSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        Summary result = dataSource.query("select sum(total_micros), sum(transaction_count)"
                + " from overall_aggregate where transaction_type = ? and capture_time >= ?"
                + " and capture_time <= ?", new OverallSummaryResultSetExtractor(),
                transactionType, captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return new Summary(null, 0, 0);
        } else {
            return result;
        }
    }

    public QueryResult<Summary> readTransactionSummaries(String transactionType,
            long captureTimeFrom, long captureTimeTo, int limit) throws SQLException {
        // it's important that all these columns are in a single index so h2 can return the
        // result set directly from the index without having to reference the table for each row
        ImmutableList<Summary> transactions = dataSource.query("select transaction_name,"
                + " sum(total_micros), sum(transaction_count) from transaction_aggregate where"
                + " transaction_type = ? and capture_time >= ? and capture_time <= ? group by"
                + " transaction_name order by sum(total_micros) desc, transaction_name"
                + " limit ?", new SummaryRowMapper(), transactionType, captureTimeFrom,
                captureTimeTo, limit + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(transactions, limit);
    }

    public ImmutableList<CharSource> readProfiles(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select profile_id from transaction_aggregate where"
                + " transaction_type = ? and transaction_name = ? and capture_time >= ?"
                + " and capture_time <= ? and profile_id is not null",
                new CharSourceRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    public ErrorCount readOverallErrorCount(long captureTimeFrom, long captureTimeTo)
            throws SQLException {
        ErrorCount result = dataSource.query("select sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where capture_time >= ? and capture_time <= ?",
                new OverallErrorCountResultSetExtractor(), captureTimeFrom, captureTimeTo);
        if (result == null) {
            // this can happen if datasource is in the middle of closing
            return new ErrorCount(null, null, 0, 0);
        } else {
            return result;
        }
    }

    public QueryResult<ErrorCount> readTransactionErrorCounts(long captureTimeFrom,
            long captureTimeTo, int limit) throws SQLException {
        ImmutableList<ErrorCount> errorCounts = dataSource.query("select transaction_type,"
                + " transaction_name, sum(error_count), sum(transaction_count) from"
                + " transaction_aggregate where capture_time >= ? and capture_time <= ?"
                + " group by transaction_type, transaction_name having sum(error_count) > 0"
                + " order by sum(error_count) desc, transaction_type, transaction_name"
                + " limit ?", new ErrorCountRowMapper(), captureTimeFrom, captureTimeTo,
                limit + 1);
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(errorCounts, limit);
    }

    public ImmutableList<ErrorPoint> readOverallErrorPoints(long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, sum(error_count), sum(transaction_count)"
                + " from overall_aggregate where capture_time >= ? and capture_time <= ?"
                + " group by capture_time having sum(error_count) > 0 order by capture_time",
                new ErrorPointRowMapper(), captureTimeFrom, captureTimeTo);
    }

    public ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.query("select capture_time, error_count, transaction_count from"
                + " transaction_aggregate where transaction_type = ? and transaction_name = ?"
                + " and capture_time >= ? and capture_time <= ? and error_count > 0",
                new ErrorPointRowMapper(), transactionType, transactionName, captureTimeFrom,
                captureTimeTo);
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table overall_aggregate");
            dataSource.execute("truncate table transaction_aggregate");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    // TODO delete 100 at a time similar to TraceDao.deleteBefore()
    void deleteBefore(long captureTime) {
        try {
            dataSource.update("delete from overall_aggregate where capture_time < ?", captureTime);
            dataSource.update("delete from transaction_aggregate where capture_time < ?",
                    captureTime);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private class AggregateRowMapper implements RowMapper<Aggregate> {
        private final String transactionType;
        @Nullable
        private final String transactionName;
        private AggregateRowMapper(String transactionType, @Nullable String transactionName) {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }
        @Override
        public Aggregate mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long totalMicros = resultSet.getLong(2);
            long errorCount = resultSet.getLong(3);
            long transactionCount = resultSet.getLong(4);
            long profileSampleCount = resultSet.getLong(5);
            String profileId = resultSet.getString(6);
            String metrics = resultSet.getString(7);
            if (metrics == null) {
                // transaction_name should never be null
                // TODO provide better fallback here
                throw new SQLException("Found null metrics in transaction_aggregate table");
            }
            return new Aggregate(transactionType, transactionName, captureTime, totalMicros,
                    errorCount, transactionCount, metrics, getExistence(profileId),
                    profileSampleCount, null);
        }
        private Existence getExistence(@Nullable String fileBlockId) throws SQLException {
            if (fileBlockId == null) {
                return Existence.NO;
            }
            FileBlock fileBlock;
            try {
                fileBlock = FileBlock.from(fileBlockId);
            } catch (InvalidBlockIdFormatException e) {
                throw new SQLException(e);
            }
            if (cappedDatabase.isExpired(fileBlock)) {
                return Existence.EXPIRED;
            } else {
                return Existence.YES;
            }
        }
    }

    private static class OverallSummaryResultSetExtractor implements ResultSetExtractor<Summary> {
        @Override
        public Summary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                logger.warn("aggregate query did not return any results");
                return new Summary(null, 0, 0);
            }
            long totalMicros = resultSet.getLong(1);
            long transactionCount = resultSet.getLong(2);
            return new Summary(null, totalMicros, transactionCount);
        }
    }

    private static class SummaryRowMapper implements RowMapper<Summary> {
        @Override
        public Summary mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            if (transactionName == null) {
                // transaction_name should never be null
                logger.warn("found null transaction_name in transaction_aggregate");
                transactionName = "unknown";
            }
            long totalMicros = resultSet.getLong(2);
            long transactionCount = resultSet.getLong(3);
            return new Summary(transactionName, totalMicros, transactionCount);
        }
    }

    private class CharSourceRowMapper implements RowMapper<CharSource> {
        @Override
        public CharSource mapRow(ResultSet resultSet) throws SQLException {
            String profileId = resultSet.getString(1);
            // this checkNotNull is safe since the query above restricts the results
            // to those where profile_id is not null
            checkNotNull(profileId);
            FileBlock fileBlock;
            try {
                fileBlock = FileBlock.from(profileId);
            } catch (InvalidBlockIdFormatException e) {
                throw new SQLException(e);
            }
            return cappedDatabase.read(fileBlock, "{\"overwritten\":true}");
        }
    }

    private static class OverallErrorCountResultSetExtractor implements
            ResultSetExtractor<ErrorCount> {
        @Override
        public ErrorCount extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                logger.warn("aggregate query did not return any results");
                return new ErrorCount(null, null, 0, 0);
            }
            long errorCount = resultSet.getLong(1);
            long transactionCount = resultSet.getLong(2);
            return new ErrorCount(null, null, errorCount, transactionCount);
        }
    }

    private static class ErrorCountRowMapper implements RowMapper<ErrorCount> {
        @Override
        public ErrorCount mapRow(ResultSet resultSet) throws SQLException {
            String transactionType = resultSet.getString(1);
            String transactionName = resultSet.getString(2);
            long errorCount = resultSet.getLong(3);
            long transactionCount = resultSet.getLong(4);
            return new ErrorCount(Strings.nullToEmpty(transactionType),
                    Strings.nullToEmpty(transactionName), errorCount, transactionCount);
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<ErrorPoint> {
        @Override
        public ErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            long transactionCount = resultSet.getLong(3);
            return new ErrorPoint(captureTime, errorCount, transactionCount);
        }
    }
}
