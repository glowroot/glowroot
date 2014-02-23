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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
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
            new Column("total_millis", Types.BIGINT),
            new Column("count", Types.BIGINT),
            new Column("stored_trace_count", Types.BIGINT));

    private static final ImmutableList<Column> transactionAggregateColumns = ImmutableList.of(
            new Column("transaction_name", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("total_millis", Types.BIGINT),
            new Column("count", Types.BIGINT),
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

    // returns list ordered and limited by average descending
    public ImmutableList<TransactionAggregate> readTransactionAggregates(long captureTimeFrom,
            long captureTimeTo, int limit) {
        logger.debug("readTransactionAggregates(): captureTimeFrom={}, captureTimeTo={}, limit={}",
                captureTimeFrom, captureTimeTo, limit);
        return readTransactionAggregates(captureTimeFrom, captureTimeTo, limit, "");
    }

    // returns list ordered and limited by average descending
    public ImmutableList<TransactionAggregate> readBgTransactionAggregate(long captureTimeFrom,
            long captureTimeTo, int limit) {
        logger.debug("readBgTransactionAggregate(): captureTimeFrom={}, captureTimeTo={},"
                + " limit={}", captureTimeFrom, captureTimeTo, limit);
        return readTransactionAggregates(captureTimeFrom, captureTimeTo, limit, "bg_");
    }

    public void deleteAllAggregates() {
        logger.debug("deleteAllAggregates()");
        try {
            dataSource.execute("truncate table overall_aggregate");
            dataSource.execute("truncate table transaction_aggregate");
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
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void store(final long captureTime, Aggregate overall,
            final Map<String, Aggregate> transactionAggregates, String tablePrefix) {
        try {
            dataSource.update("insert into " + tablePrefix + "overall_aggregate (capture_time,"
                    + " total_millis, count, stored_trace_count) values (?, ?, ?, ?)", captureTime,
                    overall.getTotalMillis(), overall.getCount(),
                    overall.getStoredTraceCount());
            dataSource.batchUpdate("insert into " + tablePrefix + "transaction_aggregate"
                    + " (transaction_name, capture_time, total_millis, count, stored_trace_count)"
                    + " values (?, ?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Entry<String, Aggregate> entry : transactionAggregates.entrySet()) {
                        preparedStatement.setString(1, entry.getKey());
                        preparedStatement.setLong(2, captureTime);
                        Aggregate aggregate = entry.getValue();
                        preparedStatement.setLong(3, aggregate.getTotalMillis());
                        preparedStatement.setLong(4, aggregate.getCount());
                        preparedStatement.setLong(5, aggregate.getStoredTraceCount());
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
            return dataSource.query("select capture_time, total_millis, count, stored_trace_count"
                    + " from " + tablePrefix + "overall_aggregate where capture_time >= ? and"
                    + " capture_time <= ?", ImmutableList.of(captureTimeFrom, captureTimeTo),
                    new AggregateIntervalRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private ImmutableList<TransactionAggregate> readTransactionAggregates(long captureTimeFrom,
            long captureTimeTo, int limit, String tablePrefix) {
        try {
            return dataSource.query("select transaction_name, sum(total_millis), sum(count),"
                    + " sum(stored_trace_count) from " + tablePrefix + "transaction_aggregate"
                    + " where capture_time >= ? and capture_time <= ? group by transaction_name"
                    + " order by sum(total_millis) / sum(count) desc limit ?",
                    ImmutableList.of(captureTimeFrom, captureTimeTo, limit),
                    new TransactionAggregateRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @ThreadSafe
    private static class AggregateIntervalRowMapper implements RowMapper<AggregatePoint> {

        @Override
        public AggregatePoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long totalMillis = resultSet.getLong(2);
            long count = resultSet.getLong(3);
            long storedTraceCount = resultSet.getLong(4);
            return new AggregatePoint(captureTime, totalMillis, count, storedTraceCount);
        }
    }

    @ThreadSafe
    private static class TransactionAggregateRowMapper implements RowMapper<TransactionAggregate> {

        @Override
        public TransactionAggregate mapRow(ResultSet resultSet) throws SQLException {
            String name = resultSet.getString(1);
            long totalMillis = resultSet.getLong(2);
            long count = resultSet.getLong(3);
            long storedTraceCount = resultSet.getLong(4);
            return new TransactionAggregate(name, totalMillis, count, storedTraceCount);
        }
    }
}
