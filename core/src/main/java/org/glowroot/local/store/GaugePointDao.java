/*
 * Copyright 2014-2015 the original author or authors.
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
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.collector.GaugePoint;
import org.glowroot.collector.GaugePointRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;

import static org.glowroot.common.Checkers.castUntainted;

public class GaugePointDao implements GaugePointRepository {

    private static final ImmutableList<Column> gaugePointColumns = ImmutableList.<Column>of(
            Column.of("gauge_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE));

    private static final ImmutableList<Index> gaugePointIndexes =
            ImmutableList.<Index>of(Index.of("gauge_point_idx",
                    ImmutableList.of("gauge_id", "capture_time", "value")));

    private static final ImmutableList<Column> gaugePointRollupColumns = ImmutableList.<Column>of(
            Column.of("gauge_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE),
            Column.of("count", Types.DOUBLE)); // count is needed for further rollups

    private static final ImmutableList<Index> gaugePointRollupIndexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_1_idx",
                    ImmutableList.of("gauge_id", "capture_time", "value")));

    private final GaugeDao gaugeDao;
    private final DataSource dataSource;
    private final Clock clock;
    private final long fixedRollupMillis;
    private volatile long lastRollupTime;

    GaugePointDao(ConfigService configService, DataSource dataSource, Clock clock,
            long fixedRollupSeconds) throws SQLException {
        gaugeDao = GaugeDao.create(configService, dataSource);
        this.dataSource = dataSource;
        this.clock = clock;
        fixedRollupMillis = fixedRollupSeconds * 1000;
        dataSource.syncTable("gauge_point", gaugePointColumns);
        dataSource.syncIndexes("gauge_point", gaugePointIndexes);
        dataSource.syncTable("gauge_point_rollup_1", gaugePointRollupColumns);
        dataSource.syncIndexes("gauge_point_rollup_1", gaugePointRollupIndexes);
        // TODO initial rollup in case store is not called in a reasonable time
        lastRollupTime = dataSource.queryForLong(
                "select ifnull(max(capture_time), 0) from gauge_point_rollup_1");
    }

    // synchronization is needed for rollup logic
    @Override
    public void store(final List<GaugePoint> gaugePoints) throws Exception {
        if (gaugePoints.isEmpty()) {
            return;
        }
        dataSource.batchUpdate("insert into gauge_point (gauge_id, capture_time, value)"
                + " values (?, ?, ?)", new BatchAdder() {
            @Override
            public void addBatches(PreparedStatement preparedStatement) throws SQLException {
                for (GaugePoint gaugePoint : gaugePoints) {
                    Long gaugeId = gaugeDao.getGaugeId(gaugePoint.gaugeName());
                    if (gaugeId == null) {
                        // there is slight race condition on creating a new gauge, this just means
                        // it was barely created, ok to miss this measurement;
                        continue;
                    }
                    preparedStatement.setLong(1, gaugeId);
                    preparedStatement.setLong(2, gaugePoint.captureTime());
                    preparedStatement.setDouble(3, gaugePoint.value());
                    preparedStatement.addBatch();
                }
            }
        });
        // clock can never go backwards and future gauge captures will wait until this method
        // completes since ScheduledExecutorService.scheduleAtFixedRate() guarantees that future
        // invocations of GaugeCollector will wait until prior invocations complete
        //
        // TODO this clock logic will fail if remote collectors are introduced
        long safeRollupTime = clock.currentTimeMillis() - 1;
        safeRollupTime =
                (long) Math.floor(safeRollupTime / (double) fixedRollupMillis) * fixedRollupMillis;
        if (safeRollupTime > lastRollupTime) {
            rollup(lastRollupTime, safeRollupTime);
            lastRollupTime = safeRollupTime;
        }
    }

    public ImmutableList<GaugePoint> readGaugePoints(String gaugeName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws SQLException {
        String tableName = "gauge_point";
        if (rollupLevel > 0) {
            tableName += "_rollup_" + castUntainted(rollupLevel);
        }
        Long gaugeId = gaugeDao.getGaugeId(gaugeName);
        if (gaugeId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        return dataSource.query("select capture_time, value from " + tableName
                + " where gauge_id = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new GaugePointRowMapper(gaugeName), gaugeId,
                captureTimeFrom, captureTimeTo);
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table gauge_point");
        dataSource.execute("truncate table gauge_point_rollup_1");
    }

    void deleteBefore(long captureTime) throws SQLException {
        deleteBefore("gauge_point", captureTime);
        deleteBefore("gauge_point_rollup_1", captureTime);
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

    private void rollup(long lastRollupTime, long safeRollupTime) throws SQLException {
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil(capture_time / " + fixedRollupMillis + ".0) * "
                + fixedRollupMillis);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, false);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, true);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, @Untainted String captureTimeSql,
            boolean everIncreasing) throws SQLException {
        String aggregateFunction = everIncreasing ? "max" : "avg";
        dataSource.update("insert into gauge_point_rollup_1 (gauge_id, capture_time, value,"
                + " count) select gauge_id, " + captureTimeSql + " ceil_capture_time, "
                + aggregateFunction + "(value), count(*) from gauge_point gp, gauge g"
                + " where gp.capture_time > ? and gp.capture_time <= ? and gp.gauge_id = g.id"
                + " and g.ever_increasing = ?  group by gp.gauge_id, ceil_capture_time",
                lastRollupTime, safeRollupTime, everIncreasing);
    }

    private static class GaugePointRowMapper implements RowMapper<GaugePoint> {

        private final String gaugeName;

        public GaugePointRowMapper(String gaugeName) {
            this.gaugeName = gaugeName;
        }

        @Override
        public GaugePoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            double value = resultSet.getDouble(2);
            return GaugePoint.builder()
                    .gaugeName(gaugeName)
                    .captureTime(captureTime)
                    .value(value)
                    .build();
        }
    }
}
