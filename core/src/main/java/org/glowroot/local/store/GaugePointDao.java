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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common.Checkers.castUntainted;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.checkerframework.checker.tainting.qual.Untainted;
import org.glowroot.collector.GaugePoint;
import org.glowroot.collector.GaugePointRepository;
import org.glowroot.common.Clock;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;

import com.google.common.collect.ImmutableList;

public class GaugePointDao implements GaugePointRepository {

    public static final long ROLLUP_THRESHOLD_MILLIS = HOURS.toMillis(1);

    private static final ImmutableList<Column> gaugePointColumns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE));

    private static final ImmutableList<Index> gaugePointIndexes =
            ImmutableList.<Index>of(Index.of("gauge_point_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private static final ImmutableList<Column> gaugePointRollupColumns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE),
            Column.of("count", Types.DOUBLE)); // count is needed for further rollups

    private static final ImmutableList<Index> gaugePointRollupIndexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_1_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private final GaugeMetaDao gaugeMetaDao;
    private final DataSource dataSource;
    private final Clock clock;
    private final long fixedRollupMillis;
    private volatile long lastRollupTime;

    GaugePointDao(DataSource dataSource, Clock clock, long fixedRollupSeconds) throws SQLException {
        gaugeMetaDao = new GaugeMetaDao(dataSource);
        this.dataSource = dataSource;
        this.clock = clock;
        fixedRollupMillis = fixedRollupSeconds * 1000;
        // upgrade from 0.8 to 0.8.1
        dataSource.renameColumn("gauge_point", "gauge_id", "gauge_meta_id");
        dataSource.renameColumn("gauge_point_rollup_1", "gauge_id", "gauge_meta_id");
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
        dataSource.batchUpdate("insert into gauge_point (gauge_meta_id, capture_time, value)"
                + " values (?, ?, ?)", new BatchAdder() {
                    @Override
                    public void addBatches(PreparedStatement preparedStatement)
                            throws SQLException {
                        for (GaugePoint gaugePoint : gaugePoints) {
                            // everIncreasing must be supplied when calling this method
                            Boolean everIncreasing = gaugePoint.everIncreasing();
                            checkNotNull(everIncreasing);
                            long gaugeMetaId = gaugeMetaDao.getOrCreateGaugeMetaId(
                                    gaugePoint.gaugeName(), everIncreasing);
                            preparedStatement.setLong(1, gaugeMetaId);
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
        Long gaugeMetaId = gaugeMetaDao.getGaugeMetaId(gaugeName);
        if (gaugeMetaId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        return dataSource.query("select capture_time, value from " + tableName
                + " where gauge_meta_id = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new GaugePointRowMapper(gaugeName), gaugeMetaId,
                captureTimeFrom, captureTimeTo);
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table gauge_point");
        dataSource.execute("truncate table gauge_point_rollup_1");
    }

    void deleteBefore(long captureTime) throws SQLException {
        dataSource.deleteBefore("gauge_point", captureTime);
        dataSource.deleteBefore("gauge_point_rollup_1", captureTime);
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
        dataSource.update("insert into gauge_point_rollup_1 (gauge_meta_id, capture_time, value,"
                + " count) select gauge_meta_id, " + captureTimeSql + " ceil_capture_time, "
                + aggregateFunction + "(value), count(*) from gauge_point gp, gauge_meta gm"
                + " where gp.capture_time > ? and gp.capture_time <= ?"
                + " and gp.gauge_meta_id = gm.id and gm.ever_increasing = ?"
                + " group by gp.gauge_meta_id, ceil_capture_time", lastRollupTime, safeRollupTime,
                everIncreasing);
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
