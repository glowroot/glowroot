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

    private static final ImmutableList<Column> aggregateColumns = ImmutableList.of(
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("duration_total", Types.BIGINT),
            new Column("trace_count", Types.BIGINT));

    private static final ImmutableList<Column> groupingAggregateColumns = ImmutableList.of(
            new Column("grouping", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT), // capture time rounded up to nearest 5 min
            new Column("duration_total", Types.BIGINT),
            new Column("trace_count", Types.BIGINT));

    private final DataSource dataSource;

    AggregateDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("aggregate", aggregateColumns);
        dataSource.syncTable("grouping_aggregate", groupingAggregateColumns);
    }

    @Override
    public void store(final long captureTime, Aggregate aggregate,
            final Map<String, Aggregate> groupingAggregates) {
        logger.debug("store(): captureTime={}, aggregate={}, groupingAggregates={}", captureTime,
                aggregate, groupingAggregates);
        try {
            dataSource.update("insert into aggregate (capture_time, duration_total, trace_count)"
                    + " values (?, ?, ?)", captureTime, aggregate.getDurationTotal(),
                    aggregate.getTraceCount());
            dataSource.batchUpdate("insert into grouping_aggregate (grouping, capture_time,"
                    + " duration_total, trace_count) values (?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Entry<String, Aggregate> entry : groupingAggregates.entrySet()) {
                        preparedStatement.setString(1, entry.getKey());
                        preparedStatement.setLong(2, captureTime);
                        Aggregate aggregate = entry.getValue();
                        preparedStatement.setLong(3, aggregate.getDurationTotal());
                        preparedStatement.setLong(4, aggregate.getTraceCount());
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public ImmutableList<AggregatePoint> readAggregates(long captureTimeFrom, long captureTimeTo) {
        logger.debug("readAggregates(): captureTimeFrom={}, captureTimeTo={}", captureTimeFrom,
                captureTimeTo);
        try {
            return dataSource.query("select capture_time, duration_total, trace_count from"
                    + " aggregate where capture_time >= ? and capture_time <= ?",
                    ImmutableList.of(captureTimeFrom, captureTimeTo),
                    new AggregateIntervalRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    // returns list ordered and limited by average descending
    public ImmutableList<GroupingAggregate> readGroupingAggregates(long captureTimeFrom,
            long captureTimeTo, int limit) {
        logger.debug("readAggregates(): captureTimeFrom={}, captureTimeTo={}, limit={}",
                captureTimeFrom, captureTimeTo, limit);
        try {
            return dataSource.query("select grouping, sum(duration_total), sum(trace_count) from"
                    + " grouping_aggregate where capture_time >= ? and capture_time <= ? group by"
                    + " grouping order by sum(duration_total) / sum(trace_count) desc limit ?",
                    ImmutableList.of(captureTimeFrom, captureTimeTo, limit),
                    new GroupingAggregateRowMapper());
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
            long durationTotal = resultSet.getLong(2);
            long traceCount = resultSet.getLong(3);
            return new AggregatePoint(captureTime, durationTotal, traceCount);
        }
    }

    @ThreadSafe
    private static class GroupingAggregateRowMapper implements RowMapper<GroupingAggregate> {

        @Override
        public GroupingAggregate mapRow(ResultSet resultSet) throws SQLException {
            String grouping = resultSet.getString(1);
            long durationTotal = resultSet.getLong(2);
            long traceCount = resultSet.getLong(3);
            return new GroupingAggregate(grouping, durationTotal, traceCount);
        }
    }
}
