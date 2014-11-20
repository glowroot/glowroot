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

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.ImmutableAggregate;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;

public class AggregateDao implements AggregateRepository {

    private static final Logger logger = LoggerFactory.getLogger(AggregateDao.class);

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", Types.VARCHAR),
                    // capture time rounded up to nearest 5 min
                    ImmutableColumn.of("capture_time", Types.BIGINT),
                    ImmutableColumn.of("total_micros", Types.BIGINT), // microseconds
                    ImmutableColumn.of("error_count", Types.BIGINT),
                    ImmutableColumn.of("transaction_count", Types.BIGINT),
                    ImmutableColumn.of("metrics", Types.VARCHAR)); // json data

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
                    ImmutableColumn.of("profile_id", Types.VARCHAR),  // capped database id
                    ImmutableColumn.of("metrics", Types.VARCHAR)); // json data

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
    private static final ImmutableList<Index> transactionAggregateIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("transaction_aggregate_idx", ImmutableList.of("capture_time",
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
                                overallAggregate.transactionType());
                        preparedStatement.setLong(2, overallAggregate.captureTime());
                        preparedStatement.setLong(3, overallAggregate.totalMicros());
                        preparedStatement.setLong(4, overallAggregate.errorCount());
                        preparedStatement.setLong(5, overallAggregate.transactionCount());
                        preparedStatement.setString(6, overallAggregate.metrics());
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
                        String profile = transactionAggregate.profile();
                        if (profile != null) {
                            profileId = cappedDatabase.write(CharSource.wrap(profile)).getId();
                        }
                        preparedStatement.setString(1,
                                transactionAggregate.transactionType());
                        preparedStatement.setString(2,
                                transactionAggregate.transactionName());
                        preparedStatement.setLong(3, transactionAggregate.captureTime());
                        preparedStatement.setLong(4, transactionAggregate.totalMicros());
                        preparedStatement.setLong(5, transactionAggregate.errorCount());
                        preparedStatement.setLong(6, transactionAggregate.transactionCount());
                        preparedStatement.setLong(7,
                                transactionAggregate.profileSampleCount());
                        preparedStatement.setString(8, profileId);
                        preparedStatement.setString(9, transactionAggregate.metrics());
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
            return ImmutableSummary.builder().build();
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
            return ImmutableErrorCount.builder().build();
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

    void deleteBefore(long captureTime) {
        try {
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
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private class AggregateRowMapper implements RowMapper<Aggregate> {
        private final String transactionType;
        private final @Nullable String transactionName;
        private AggregateRowMapper(String transactionType, @Nullable String transactionName) {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
        }
        @Override
        public Aggregate mapRow(ResultSet resultSet) throws SQLException {
            String metrics = resultSet.getString(7);
            if (metrics == null) {
                throw new SQLException("Found null metrics in transaction_aggregate table");
            }
            return ImmutableAggregate.builder()
                    .transactionType(transactionType)
                    .transactionName(transactionName)
                    .captureTime(resultSet.getLong(1))
                    .totalMicros(resultSet.getLong(2))
                    .errorCount(resultSet.getLong(3))
                    .transactionCount(resultSet.getLong(4))
                    .metrics(checkNotNull(resultSet.getString(7))) // metrics should never be null
                    .profileExistence(
                            RowMappers.getExistence(resultSet.getString(6), cappedDatabase))
                    .profileSampleCount(resultSet.getLong(5))
                    .build();
        }
    }

    private static class OverallSummaryResultSetExtractor implements ResultSetExtractor<Summary> {
        @Override
        public Summary extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableSummary.builder()
                    .totalMicros(resultSet.getLong(1))
                    .transactionCount(resultSet.getLong(2))
                    .build();
        }
    }

    private static class SummaryRowMapper implements RowMapper<Summary> {
        @Override
        public Summary mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            if (transactionName == null) {
                // transaction_name should never be null
                throw new SQLException("Found null transaction_name in transaction_aggregate");
            }
            return ImmutableSummary.builder()
                    .transactionName(transactionName)
                    .totalMicros(resultSet.getLong(2))
                    .transactionCount(resultSet.getLong(3))
                    .build();
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
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableErrorCount.builder()
                    .errorCount(resultSet.getLong(1))
                    .transactionCount(resultSet.getLong(2))
                    .build();
        }
    }

    private static class ErrorCountRowMapper implements RowMapper<ErrorCount> {
        @Override
        public ErrorCount mapRow(ResultSet resultSet) throws SQLException {
            return ImmutableErrorCount.builder()
                    .transactionType(Strings.nullToEmpty(resultSet.getString(1)))
                    .transactionName(Strings.nullToEmpty(resultSet.getString(2)))
                    .errorCount(resultSet.getLong(3))
                    .transactionCount(resultSet.getLong(4))
                    .build();
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
