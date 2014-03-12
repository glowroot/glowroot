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
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class AggregateDao implements AggregateRepository {

    private static final Logger logger = LoggerFactory.getLogger(AggregateDao.class);

    private static final ImmutableList<Column> overallAggregateColumns = ImmutableList.of(
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_duration", Types.BIGINT), // nanoseconds
            new Column("count", Types.BIGINT),
            new Column("error_count", Types.BIGINT),
            new Column("stored_trace_count", Types.BIGINT));

    private static final ImmutableList<Column> transactionAggregateColumns = ImmutableList.of(
            new Column("transaction_name", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_duration", Types.BIGINT), // nanoseconds
            new Column("count", Types.BIGINT),
            new Column("error_count", Types.BIGINT),
            new Column("stored_trace_count", Types.BIGINT));

    private final DataSource dataSource;

    AggregateDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("overall_aggregate", overallAggregateColumns);
        dataSource.syncTable("transaction_aggregate", transactionAggregateColumns);
        dataSource.syncTable("bg_overall_aggregate", overallAggregateColumns);
        dataSource.syncTable("bg_transaction_aggregate", transactionAggregateColumns);
    }

    @Override
    public void store(long captureTime, Aggregate overallAggregate,
            Map<String, Aggregate> transactionAggregates, Aggregate bgOverallAggregate,
            Map<String, Aggregate> bgTransactionAggregates) {
        logger.debug("store(): captureTime={}, overallAggregate={}, transactionAggregates={},"
                + " bgOverallAggregate={}, bgTransactionAggregates={}", captureTime,
                overallAggregate, transactionAggregates, bgOverallAggregate,
                bgTransactionAggregates);
        store(captureTime, overallAggregate, transactionAggregates, "");
        store(captureTime, bgOverallAggregate, bgTransactionAggregates, "bg_");
    }

    public ImmutableList<AggregatePoint> readPoints(long captureTimeFrom, long captureTimeTo) {
        logger.debug("readAggregates(): captureTimeFrom={}, captureTimeTo={}", captureTimeFrom,
                captureTimeTo);
        return readPoints(captureTimeFrom, captureTimeTo, "");
    }

    public ImmutableList<AggregatePoint> readBgPoints(long captureTimeFrom, long captureTimeTo) {
        logger.debug("readBgAggregates(): captureTimeFrom={}, captureTimeTo={}", captureTimeFrom,
                captureTimeTo);
        return readPoints(captureTimeFrom, captureTimeTo, "bg_");
    }

    public Map<String, Map<Long, AggregatePoint>> readTransactionPoints(long captureTimeFrom,
            long captureTimeTo, List<String> transactionNames) {
        logger.debug("readTransactionPoints(): captureTimeFrom={}, captureTimeTo={},"
                + " transactionNames={}", captureTimeFrom, captureTimeTo, transactionNames);
        return readTransactionPoints(captureTimeFrom, captureTimeTo, transactionNames, "");
    }

    public OverallAggregate readOverallAggregate(long captureTimeFrom,
            long captureTimeTo) {
        logger.debug("readOverallAggregate(): captureTimeFrom={}, captureTimeTo={}",
                captureTimeFrom, captureTimeTo);
        return readOverallAggregate(captureTimeFrom, captureTimeTo, "");
    }

    public OverallAggregate readBgOverallAggregate(long captureTimeFrom,
            long captureTimeTo) {
        logger.debug("readBgOverallAggregate(): captureTimeFrom={}, captureTimeTo={}",
                captureTimeFrom, captureTimeTo);
        return readOverallAggregate(captureTimeFrom, captureTimeTo, "bg_");
    }

    // returns list ordered and limited by average descending
    public ImmutableList<TransactionAggregate> readTransactionAggregates(long captureTimeFrom,
            long captureTimeTo, TransactionAggregateSortColumn sortColumn,
            SortDirection sortDirection, int limit) {
        logger.debug("readTransactionAggregate(): captureTimeFrom={}, captureTimeTo={},"
                + " sortColumn={}, sortDirection={}, limit={}", captureTimeFrom, captureTimeTo,
                sortColumn, sortDirection, limit);
        return readTransactionAggregates(captureTimeFrom, captureTimeTo, sortColumn,
                sortDirection, limit, "");
    }

    // returns list ordered and limited by average descending
    public ImmutableList<TransactionAggregate> readBgTransactionAggregate(long captureTimeFrom,
            long captureTimeTo, TransactionAggregateSortColumn sortColumn,
            SortDirection sortDirection, int limit) {
        logger.debug("readBgTransactionAggregate(): captureTimeFrom={}, captureTimeTo={},"
                + " sortColumn={}, sortDirection={}, limit={}", captureTimeFrom, captureTimeTo,
                sortColumn, sortDirection, limit);
        return readTransactionAggregates(captureTimeFrom, captureTimeTo, sortColumn,
                sortDirection, limit, "bg_");
    }

    public void deleteAllAggregates() {
        logger.debug("deleteAllAggregates()");
        try {
            dataSource.execute("truncate table overall_aggregate");
            dataSource.execute("truncate table transaction_aggregate");
            dataSource.execute("truncate table bg_overall_aggregate");
            dataSource.execute("truncate table bg_transaction_aggregate");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteAggregatesBefore(long captureTime) {
        logger.debug("deleteAggregatesBefore(): captureTime={}", captureTime);
        try {
            dataSource.update("delete from overall_aggregate where capture_time < ?", captureTime);
            dataSource.update("delete from transaction_aggregate where capture_time < ?",
                    captureTime);
            dataSource.update("delete from bg_overall_aggregate where capture_time < ?",
                    captureTime);
            dataSource.update("delete from bg_transaction_aggregate where capture_time < ?",
                    captureTime);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void store(final long captureTime, Aggregate overall,
            final Map<String, Aggregate> transactionAggregates, String tablePrefix) {
        try {
            dataSource.update("insert into " + tablePrefix + "overall_aggregate (capture_time,"
                    + " total_duration, count, error_count, stored_trace_count) values"
                    + " (?, ?, ?, ?, ?)", captureTime, overall.getTotalDuration(),
                    overall.getCount(), overall.getErrorCount(), overall.getStoredTraceCount());
            dataSource.batchUpdate("insert into " + tablePrefix + "transaction_aggregate"
                    + " (transaction_name, capture_time, total_duration, count, error_count,"
                    + " stored_trace_count) values (?, ?, ?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Entry<String, Aggregate> entry : transactionAggregates.entrySet()) {
                        preparedStatement.setString(1, entry.getKey());
                        preparedStatement.setLong(2, captureTime);
                        Aggregate aggregate = entry.getValue();
                        preparedStatement.setDouble(3, aggregate.getTotalDuration());
                        preparedStatement.setLong(4, aggregate.getCount());
                        preparedStatement.setLong(5, aggregate.getErrorCount());
                        preparedStatement.setLong(6, aggregate.getStoredTraceCount());
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private ImmutableList<AggregatePoint> readPoints(long captureTimeFrom, long captureTimeTo,
            String tablePrefix) {
        try {
            return dataSource.query("select capture_time, total_duration, count, error_count,"
                    + " stored_trace_count from " + tablePrefix + "overall_aggregate where"
                    + " capture_time >= ? and capture_time <= ? order by capture_time",
                    ImmutableList.of(captureTimeFrom, captureTimeTo),
                    new AggregatePointRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private Map<String, Map<Long, AggregatePoint>> readTransactionPoints(
            long captureTimeFrom, long captureTimeTo, List<String> transactionNames,
            String tablePrefix) {
        if (transactionNames.isEmpty()) {
            return ImmutableMap.of();
        }
        StringBuilder sql = new StringBuilder();
        sql.append("select transaction_name, capture_time, total_duration, count, error_count,");
        sql.append(" stored_trace_count from ");
        sql.append(tablePrefix);
        sql.append("transaction_aggregate where capture_time >= ? and capture_time <= ? and");
        sql.append(" transaction_name in (");
        List<Object> args = Lists.newArrayList();
        args.add(captureTimeFrom);
        args.add(captureTimeTo);
        sql.append('?');
        for (int i = 0; i < transactionNames.size() - 1; i++) {
            sql.append(", ?");
        }
        sql.append(")");
        args.addAll(transactionNames);
        try {
            Map<String, Map<Long, AggregatePoint>> result = dataSource.query(sql.toString(), args,
                    new TransactionAggregatePointResultSetExtractor(transactionNames));
            if (result == null) {
                // this can happen if datasource is in the middle of closing
                return ImmutableMap.of();
            } else {
                return result;
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableMap.of();
        }
    }

    private OverallAggregate readOverallAggregate(long captureTimeFrom, long captureTimeTo,
            String tablePrefix) {
        try {
            OverallAggregate result = dataSource.query("select sum(total_duration), sum(count),"
                    + " sum(error_count), sum(stored_trace_count) from " + tablePrefix
                    + "overall_aggregate where capture_time >= ? and capture_time <= ?",
                    ImmutableList.of(captureTimeFrom, captureTimeTo),
                    new OverallAggregateResultSetExtractor());
            if (result == null) {
                // this can happen if datasource is in the middle of closing
                return new OverallAggregate(0, 0, 0, 0);
            } else {
                return result;
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return new OverallAggregate(0, 0, 0, 0);
        }
    }

    private ImmutableList<TransactionAggregate> readTransactionAggregates(long captureTimeFrom,
            long captureTimeTo, TransactionAggregateSortColumn sortColumn,
            SortDirection sortDirection, int limit, String tablePrefix) {
        try {
            return dataSource.query("select transaction_name, sum(total_duration), sum(count),"
                    + " sum(error_count), sum(stored_trace_count) from " + tablePrefix
                    + "transaction_aggregate where capture_time >= ? and capture_time <= ?"
                    + " group by transaction_name " + getOrderByClause(sortColumn, sortDirection)
                    + " limit ?", ImmutableList.of(captureTimeFrom, captureTimeTo, limit),
                    new TransactionAggregateRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private static String getOrderByClause(TransactionAggregateSortColumn column,
            SortDirection direction) {
        switch (direction) {
            case ASC:
                return "order by " + column.getColumn();
            case DESC:
                return "order by " + column.getColumn() + " desc";
            default:
                throw new IllegalStateException("Unexpected sort direction: " + direction);
        }
    }

    public static enum TransactionAggregateSortColumn {
        TOTAL("sum(total_duration)"),
        AVERAGE("sum(total_duration) / sum(count)"),
        COUNT("sum(count)"),
        ERROR_COUNT("sum(error_count)"),
        STORED_TRACE_COUNT("sum(stored_trace_count)");

        private final String column;

        private TransactionAggregateSortColumn(String column) {
            this.column = column;
        }

        private String getColumn() {
            return column;
        }
    }

    public static enum SortDirection {
        ASC, DESC
    }

    @ThreadSafe
    private static class AggregatePointRowMapper implements RowMapper<AggregatePoint> {

        @Override
        public AggregatePoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            double totalMillis = resultSet.getLong(2) / 1000000.0;
            long count = resultSet.getLong(3);
            long errorCount = resultSet.getLong(4);
            long storedTraceCount = resultSet.getLong(5);
            return new AggregatePoint(captureTime, totalMillis, count, errorCount,
                    storedTraceCount);
        }
    }

    @ThreadSafe
    private static class TransactionAggregatePointResultSetExtractor implements
            ResultSetExtractor<Map<String, Map<Long, AggregatePoint>>> {

        private final List<String> transactionNames;

        public TransactionAggregatePointResultSetExtractor(List<String> transactionNames) {
            this.transactionNames = transactionNames;
        }

        @Override
        public Map<String, Map<Long, AggregatePoint>> extractData(ResultSet resultSet)
                throws SQLException {
            // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
            // see https://code.google.com/p/guava-libraries/issues/detail?id=635
            Map<String, Map<Long, AggregatePoint>> pointsMap =
                    new TreeMap<String, Map<Long, AggregatePoint>>(
                            Ordering.explicit(transactionNames));
            while (resultSet.next()) {
                String transactionName = resultSet.getString(1);
                long captureTime = resultSet.getLong(2);
                double totalMillis = resultSet.getLong(3) / 1000000.0;
                long count = resultSet.getLong(4);
                long errorCount = resultSet.getLong(5);
                long storedTraceCount = resultSet.getLong(6);
                AggregatePoint point = new AggregatePoint(captureTime, totalMillis, count,
                        errorCount, storedTraceCount);
                Map<Long, AggregatePoint> points = pointsMap.get(transactionName);
                if (points == null) {
                    points = Maps.newHashMap();
                    pointsMap.put(transactionName, points);
                }
                points.put(captureTime, point);
            }
            return pointsMap;
        }
    }

    @ThreadSafe
    private static class OverallAggregateResultSetExtractor implements
            ResultSetExtractor<OverallAggregate> {

        @Override
        public OverallAggregate extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                logger.warn("overall aggregate query did not return any results");
                return new OverallAggregate(0, 0, 0, 0);
            }
            double totalMillis = resultSet.getLong(1) / 1000000.0;
            long count = resultSet.getLong(2);
            long errorCount = resultSet.getLong(3);
            long storedTraceCount = resultSet.getLong(4);
            return new OverallAggregate(totalMillis, count, errorCount, storedTraceCount);
        }
    }

    @ThreadSafe
    private static class TransactionAggregateRowMapper implements RowMapper<TransactionAggregate> {

        @Override
        public TransactionAggregate mapRow(ResultSet resultSet) throws SQLException {
            String name = resultSet.getString(1);
            double totalMillis = resultSet.getLong(2) / 1000000.0;
            long count = resultSet.getLong(3);
            long errorCount = resultSet.getLong(4);
            long storedTraceCount = resultSet.getLong(5);
            return new TransactionAggregate(name, totalMillis, count, errorCount, storedTraceCount);
        }
    }
}
