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
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.TransactionPoint;
import org.glowroot.collector.TransactionPointRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.ThreadSafe;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TransactionPointDao implements TransactionPointRepository {

    private static final Logger logger = LoggerFactory.getLogger(TransactionPointDao.class);

    private static final ImmutableList<Column> overallPointColumns = ImmutableList.of(
            new Column("transaction_type", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_micros", Types.BIGINT), // microseconds
            new Column("count", Types.BIGINT),
            new Column("error_count", Types.BIGINT),
            new Column("stored_trace_count", Types.BIGINT),
            new Column("trace_metrics", Types.VARCHAR)); // json data

    private static final ImmutableList<Column> transactionPointColumns = ImmutableList.of(
            new Column("transaction_type", Types.VARCHAR),
            new Column("transaction_name", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_micros", Types.BIGINT), // microseconds
            new Column("count", Types.BIGINT),
            new Column("error_count", Types.BIGINT),
            new Column("stored_trace_count", Types.BIGINT),
            new Column("trace_metrics", Types.VARCHAR), // json data
            new Column("profile_id", Types.VARCHAR)); // capped database id

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> overallPointIndexes = ImmutableList.of(
            new Index("overall_point_idx", ImmutableList.of("transaction_type", "capture_time",
                    "total_micros", "count", "error_count", "stored_trace_count")));

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> transactionPointIndexes = ImmutableList.of(
            new Index("transaction_point_idx", ImmutableList.of("transaction_type", "capture_time",
                    "transaction_name", "total_micros", "count", "error_count",
                    "stored_trace_count")));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;

    TransactionPointDao(DataSource dataSource, CappedDatabase cappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
        dataSource.syncTable("overall_point", overallPointColumns);
        dataSource.syncIndexes("overall_point", overallPointIndexes);
        dataSource.syncTable("transaction_point", transactionPointColumns);
        dataSource.syncIndexes("transaction_point", transactionPointIndexes);
    }

    @Override
    public void store(final String transactionType, TransactionPoint overallPoint,
            final Map<String, TransactionPoint> transactionPoints) {
        try {
            dataSource.update("insert into overall_point (transaction_type, capture_time,"
                    + " total_micros, count, error_count, stored_trace_count, trace_metrics)"
                    + " values (?, ?, ?, ?, ?, ?, ?)", transactionType,
                    overallPoint.getCaptureTime(), overallPoint.getTotalMicros(),
                    overallPoint.getCount(), overallPoint.getErrorCount(),
                    overallPoint.getStoredTraceCount(), overallPoint.getTraceMetrics());
            dataSource.batchUpdate("insert into transaction_point (transaction_type,"
                    + " transaction_name, capture_time, total_micros, count, error_count,"
                    + " stored_trace_count, trace_metrics, profile_id) values"
                    + " (?, ?, ?, ?, ?, ?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Entry<String, TransactionPoint> entry : transactionPoints.entrySet()) {
                        TransactionPoint transactionPoint = entry.getValue();
                        String profileId = null;
                        String profile = transactionPoint.getProfile();
                        if (profile != null) {
                            profileId = cappedDatabase.write(CharSource.wrap(profile)).getId();
                        }
                        preparedStatement.setString(1, transactionType);
                        preparedStatement.setString(2, entry.getKey());
                        preparedStatement.setLong(3, transactionPoint.getCaptureTime());
                        preparedStatement.setLong(4, transactionPoint.getTotalMicros());
                        preparedStatement.setLong(5, transactionPoint.getCount());
                        preparedStatement.setLong(6, transactionPoint.getErrorCount());
                        preparedStatement.setLong(7,
                                transactionPoint.getStoredTraceCount());
                        preparedStatement.setString(8, transactionPoint.getTraceMetrics());
                        preparedStatement.setString(9, profileId);
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    // default transactionType is ""
    public ImmutableList<TransactionPoint> readOverallPoints(String transactionType,
            long captureTimeFrom, long captureTimeTo) {
        try {
            return dataSource.query("select capture_time, total_micros, count, error_count,"
                    + " stored_trace_count, trace_metrics from overall_point where"
                    + " transaction_type = ? and capture_time >= ? and capture_time <= ?"
                    + " order by capture_time",
                    ImmutableList.of(transactionType, captureTimeFrom, captureTimeTo),
                    new TransactionPointRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public ImmutableList<TransactionPoint> readTransactionPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) {
        try {
            // TODO this query is a little slow because of pulling in trace_metrics for each row
            // and sticking trace_metrics into index does not seem to help
            return dataSource.query("select capture_time, total_micros, count, error_count,"
                    + " stored_trace_count, trace_metrics from transaction_point where"
                    + " transaction_type = ? and transaction_name = ? and capture_time >= ?"
                    + " and capture_time <= ?",
                    ImmutableList.of(transactionType, transactionName, captureTimeFrom,
                            captureTimeTo),
                    new TransactionPointRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public Overall readOverall(String transactionType, long captureTimeFrom, long captureTimeTo) {
        try {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            Overall result = dataSource.query("select sum(total_micros), sum(count),"
                    + " sum(error_count), sum(stored_trace_count) from overall_point where"
                    + " transaction_type = ? and capture_time >= ? and capture_time <= ?",
                    ImmutableList.of(transactionType, captureTimeFrom, captureTimeTo),
                    new OverallResultSetExtractor());
            if (result == null) {
                // this can happen if datasource is in the middle of closing
                return new Overall(0, 0, 0, 0);
            } else {
                return result;
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return new Overall(0, 0, 0, 0);
        }
    }

    public QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query) {
        try {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            ImmutableList<TransactionSummary> transactions =
                    dataSource.query("select transaction_name, sum(total_micros), sum(count),"
                            + " sum(error_count), sum(stored_trace_count) from transaction_point"
                            + " where transaction_type = ? and capture_time >= ?"
                            + " and capture_time <= ? group by transaction_name "
                            + query.getSortDirection().getOrderByClause(query.getSortAttribute())
                            + " limit ?", ImmutableList.of(query.getTransactionType(),
                            query.getFrom(), query.getTo(), query.getLimit() + 1),
                            new TransactionSummaryRowMapper());
            // one extra record over the limit is fetched above to identify if the limit was hit
            return QueryResult.from(transactions, query.getLimit());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return QueryResult.empty();
        }
    }

    public ImmutableList<CharSource> readProfiles(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) {
        try {
            return dataSource.query("select profile_id from transaction_point where"
                    + " transaction_type = ? and transaction_name = ? and capture_time >= ?"
                    + " and capture_time <= ? and profile_id is not null",
                    ImmutableList.of(transactionType, transactionName, captureTimeFrom,
                            captureTimeTo),
                    new RowMapper<CharSource>() {
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
                    });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table overall_point");
            dataSource.execute("truncate table transaction_point");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    // TODO delete 100 at a time similar to SnapshotDao.deleteBefore()
    void deleteBefore(long captureTime) {
        try {
            dataSource.update("delete from overall_point where capture_time < ?", captureTime);
            dataSource.update("delete from transaction_point where capture_time < ?", captureTime);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @ThreadSafe
    private static class TransactionPointRowMapper implements RowMapper<TransactionPoint> {
        @Override
        public TransactionPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long totalMicros = resultSet.getLong(2);
            long count = resultSet.getLong(3);
            long errorCount = resultSet.getLong(4);
            long storedTraceCount = resultSet.getLong(5);
            String traceMetrics = resultSet.getString(6);
            if (traceMetrics == null) {
                // transaction_name should never be null
                // TODO provide better fallback here
                throw new SQLException("Found null trace_metrics in transaction_point table");
            }
            return new TransactionPoint(captureTime, totalMicros, count, errorCount,
                    storedTraceCount, traceMetrics, null);
        }
    }

    @ThreadSafe
    private static class OverallResultSetExtractor implements ResultSetExtractor<Overall> {
        @Override
        public Overall extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                logger.warn("aggregate query did not return any results");
                return new Overall(0, 0, 0, 0);
            }
            long totalMicros = resultSet.getLong(1);
            long count = resultSet.getLong(2);
            long errorCount = resultSet.getLong(3);
            long storedTraceCount = resultSet.getLong(4);
            return new Overall(totalMicros, count, errorCount, storedTraceCount);
        }
    }

    @ThreadSafe
    private static class TransactionSummaryRowMapper implements RowMapper<TransactionSummary> {
        @Override
        public TransactionSummary mapRow(ResultSet resultSet) throws SQLException {
            String name = resultSet.getString(1);
            if (name == null) {
                // transaction_name should never be null
                logger.warn("found null transaction_name in transaction_point");
                name = "unknown";
            }
            long totalMicros = resultSet.getLong(2);
            long count = resultSet.getLong(3);
            long errorCount = resultSet.getLong(4);
            long storedTraceCount = resultSet.getLong(5);
            return new TransactionSummary(name, totalMicros, count, errorCount, storedTraceCount);
        }
    }
}
