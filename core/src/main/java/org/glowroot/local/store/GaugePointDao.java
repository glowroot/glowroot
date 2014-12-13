/*
 * Copyright 2014 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.GaugePoint;
import org.glowroot.collector.GaugePointRepository;
import org.glowroot.collector.ImmutableGaugePoint;
import org.glowroot.common.Clock;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;

public class GaugePointDao implements GaugePointRepository {

    private static final Logger logger = LoggerFactory.getLogger(GaugePointDao.class);

    private static final ImmutableList<Column> gaugePointColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_name", Types.VARCHAR),
            ImmutableColumn.of("capture_time", Types.BIGINT),
            ImmutableColumn.of("value", Types.DOUBLE));

    private static final ImmutableList<Index> gaugePointIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("gauge_point_idx",
                    ImmutableList.of("gauge_name", "capture_time", "value")));

    private static final ImmutableList<Column> gaugePointRollupColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_name", Types.VARCHAR),
            ImmutableColumn.of("capture_time", Types.BIGINT),
            ImmutableColumn.of("value", Types.DOUBLE),
            ImmutableColumn.of("count", Types.DOUBLE)); // count is needed for further rollups

    private static final ImmutableList<Index> gaugePointRollupIndexes =
            ImmutableList.<Index>of(ImmutableIndex.of("gauge_point_rollup_1_idx",
                    ImmutableList.of("gauge_name", "capture_time", "value")));

    private final DataSource dataSource;
    private final Clock clock;
    private final long fixedRollupIntervalMillis;
    private volatile long lastRollupTime;

    GaugePointDao(DataSource dataSource, Clock clock, long fixedRollupIntervalSeconds)
            throws SQLException {
        this.dataSource = dataSource;
        this.clock = clock;
        this.fixedRollupIntervalMillis = fixedRollupIntervalSeconds * 1000;
        dataSource.syncTable("gauge_point", gaugePointColumns);
        dataSource.syncIndexes("gauge_point", gaugePointIndexes);
        dataSource.syncTable("gauge_point_rollup_1", gaugePointRollupColumns);
        dataSource.syncIndexes("gauge_point_rollup_1", gaugePointRollupIndexes);
        lastRollupTime = dataSource.queryForLong(
                "select ifnull(max(capture_time), 0) from gauge_point_rollup_1");
    }

    // synchronization is needed for rollup logic
    @Override
    public void store(final List<GaugePoint> gaugePoints) {
        if (gaugePoints.isEmpty()) {
            return;
        }
        try {
            dataSource.batchUpdate("insert into gauge_point (gauge_name, capture_time, value)"
                    + " values (?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement) throws SQLException {
                    for (GaugePoint gaugePoint : gaugePoints) {
                        preparedStatement.setString(1, gaugePoint.gaugeName());
                        preparedStatement.setLong(2, gaugePoint.captureTime());
                        preparedStatement.setDouble(3, gaugePoint.value());
                        preparedStatement.addBatch();
                    }
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
        // clock can never go backwards and future gauge captures will wait until this method
        // completes since ScheduledExecutorService.scheduleAtFixedRate() guarantees that future
        // invocations of GaugeCollector will wait until prior invocations complete
        //
        // TODO this clock logic will fail if remote collectors are introduced
        long safeRollupTime = clock.currentTimeMillis() - 1;
        safeRollupTime = (long) Math.floor(safeRollupTime / (double) fixedRollupIntervalMillis)
                * fixedRollupIntervalMillis;
        if (safeRollupTime > lastRollupTime) {
            try {
                rollup(lastRollupTime, safeRollupTime);
                lastRollupTime = safeRollupTime;
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    public ImmutableList<GaugePoint> readGaugePoints(String gaugeName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws SQLException {
        String tableName = "gauge_point";
        if (rollupLevel > 0) {
            tableName += "_rollup_" + rollupLevel;
        }
        return dataSource.query("select gauge_name, capture_time, value from " + tableName
                + " where gauge_name = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new GaugePointRowMapper(), gaugeName, captureTimeFrom,
                captureTimeTo);
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table gauge_point");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteBefore(long captureTime) {
        try {
            // delete 100 at a time, which is both faster than deleting all at once, and doesn't
            // lock the single jdbc connection for one large chunk of time
            while (true) {
                int deleted = dataSource.update("delete from gauge_point where capture_time < ?",
                        captureTime);
                if (deleted == 0) {
                    break;
                }
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void rollup(long lastRollupTime, long safeRollupTime) throws SQLException {
        // need ".0" to force double result
        dataSource.update("insert into gauge_point_rollup_1 (gauge_name, capture_time, value,"
                + " count) select gauge_name, ceil(capture_time / " + fixedRollupIntervalMillis
                + ".0) * " + fixedRollupIntervalMillis + " ceil_capture_time, avg(value), count(*)"
                + " from gauge_point where capture_time > ? and capture_time <= ?"
                + " group by gauge_name, ceil_capture_time", lastRollupTime, safeRollupTime);
    }

    private static class GaugePointRowMapper implements RowMapper<GaugePoint> {
        @Override
        public GaugePoint mapRow(ResultSet resultSet) throws SQLException {
            String gaugeName = resultSet.getString(1);
            if (gaugeName == null) {
                // gauge_name should never be null
                throw new SQLException("Found null gauge_name in gauge_point");
            }
            long captureTime = resultSet.getLong(2);
            double value = resultSet.getDouble(3);
            return ImmutableGaugePoint.builder()
                    .gaugeName(gaugeName)
                    .captureTime(captureTime)
                    .value(value)
                    .build();
        }
    }
}
