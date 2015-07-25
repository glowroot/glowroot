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
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.Checkers.castUntainted;

public class GaugePointDao implements GaugePointRepository {

    private static final ImmutableList<Column> gaugePointRollup0Columns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE));

    private static final ImmutableList<Column> gaugePointRollupXColumns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE),
            Column.of("count", Types.DOUBLE)); // count is needed for further rollups

    private static final ImmutableList<Index> gaugePointRollup0Indexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_0_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private static final ImmutableList<Index> gaugePointRollup1Indexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_1_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private static final ImmutableList<Index> gaugePointRollup2Indexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_2_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private static final ImmutableList<Column> gaugePointLastRollupTimesColumns =
            ImmutableList.<Column>of(Column.of("last_rollup_1_time", Types.BIGINT),
                    Column.of("last_rollup_2_time", Types.BIGINT));

    private final GaugeMetaDao gaugeMetaDao;
    private final DataSource dataSource;
    private final Clock clock;
    private final long fixedRollup1Millis;
    private final long fixedRollup2Millis;
    private final long rollup1ViewThresholdMillis;
    private final long rollup2ViewThresholdMillis;

    private volatile long lastRollup1Time;
    private volatile long lastRollup2Time;

    private final Object rollupLock = new Object();

    GaugePointDao(DataSource dataSource, Clock clock, long fixedRollup1Seconds,
            long fixedRollup2Seconds) throws SQLException {
        gaugeMetaDao = new GaugeMetaDao(dataSource);
        this.dataSource = dataSource;
        this.clock = clock;
        fixedRollup1Millis = fixedRollup1Seconds * 1000;
        fixedRollup2Millis = fixedRollup2Seconds * 1000;
        // default rollup1 is 1 minute, making default rollup1 view threshold 15 min
        rollup1ViewThresholdMillis = fixedRollup1Millis * 15;
        // default rollup2 is 15 minutes, making default rollup2 view threshold 4 hours
        rollup2ViewThresholdMillis = fixedRollup2Millis * 16;

        // upgrade from 0.8 to 0.8.1
        dataSource.renameColumn("gauge_point", "gauge_id", "gauge_meta_id");
        dataSource.renameColumn("gauge_point_rollup_1", "gauge_id", "gauge_meta_id");

        // upgrade from 0.8.3 to 0.8.4
        if (dataSource.tableExists("gauge_point")) {
            dataSource.execute("alter table gauge_point rename to gauge_point_rollup_0");
        }

        dataSource.syncTable("gauge_point_rollup_0", gaugePointRollup0Columns);
        dataSource.syncIndexes("gauge_point_rollup_0", gaugePointRollup0Indexes);
        dataSource.syncTable("gauge_point_rollup_1", gaugePointRollupXColumns);
        dataSource.syncIndexes("gauge_point_rollup_1", gaugePointRollup1Indexes);
        dataSource.syncTable("gauge_point_rollup_2", gaugePointRollupXColumns);
        dataSource.syncIndexes("gauge_point_rollup_2", gaugePointRollup2Indexes);
        dataSource.syncTable("gauge_point_last_rollup_times", gaugePointLastRollupTimesColumns);

        Long[] lastRollupTimes = dataSource.query("select last_rollup_1_time, last_rollup_2_time"
                + " from gauge_point_last_rollup_times", new LastRollupTimesExtractor());
        if (lastRollupTimes == null) {
            // need to populate correctly in case this is upgrade from 0.8.2
            lastRollup1Time = dataSource.queryForLong(
                    "select ifnull(max(capture_time), 0) from gauge_point_rollup_1");
            lastRollup2Time = dataSource.queryForLong(
                    "select ifnull(max(capture_time), 0) from gauge_point_rollup_2");
            dataSource.update("insert into gauge_point_last_rollup_times (last_rollup_1_time, "
                    + "last_rollup_2_time) values (?, ?)", lastRollup1Time, lastRollup2Time);
        } else {
            lastRollup1Time = lastRollupTimes[0];
            lastRollup2Time = lastRollupTimes[1];
        }

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public void store(final List<GaugePoint> gaugePoints) throws Exception {
        if (gaugePoints.isEmpty()) {
            return;
        }
        dataSource.batchUpdate("insert into gauge_point_rollup_0"
                + " (gauge_meta_id, capture_time, value) values (?, ?, ?)", new BatchAdder() {
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
        synchronized (rollupLock) {
            // clock can never go backwards and future gauge captures will wait until this method
            // completes since ScheduledExecutorService.scheduleAtFixedRate() guarantees that future
            // invocations of GaugeCollector will wait until prior invocations complete
            //
            // TODO this clock logic will fail if remote collectors are introduced
            long safeRollupTime = clock.currentTimeMillis() - 1;
            long safeRollup1Time = (long) Math.floor(safeRollupTime / (double) fixedRollup1Millis)
                    * fixedRollup1Millis;
            if (safeRollup1Time > lastRollup1Time) {
                rollup(lastRollup1Time, safeRollup1Time, fixedRollup1Millis, 1, 0);
                // JVM termination here will cause last_rollup_1_time to be out of sync, which will
                // cause a re-rollup of this time after the next startup, but this possible
                // duplicate is filtered out by the distinct clause in readGaugePoints()
                dataSource.update("update gauge_point_last_rollup_times set last_rollup_1_time = ?",
                        safeRollup1Time);
                lastRollup1Time = safeRollup1Time;
            }
            long safeRollup2Time = (long) Math.floor(safeRollupTime / (double) fixedRollup2Millis)
                    * fixedRollup2Millis;
            if (safeRollup2Time > lastRollup2Time) {
                rollup(lastRollup2Time, safeRollup2Time, fixedRollup2Millis, 2, 1);
                // JVM termination here will cause last_rollup_2_time to be out of sync, which will
                // cause a re-rollup of this time after the next startup, but this possible
                // duplicate is filtered out by the distinct clause in readGaugePoints()
                dataSource.update("update gauge_point_last_rollup_times set last_rollup_2_time = ?",
                        safeRollup2Time);
                lastRollup2Time = safeRollup2Time;
            }
        }
    }

    public ImmutableList<GaugePoint> readGaugePoints(String gaugeName, long captureTimeFrom,
            long captureTimeTo, @Untainted int rollupLevel) throws SQLException {
        String tableName = "gauge_point_rollup_" + rollupLevel;
        Long gaugeMetaId = gaugeMetaDao.getGaugeMetaId(gaugeName);
        if (gaugeMetaId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        // the distinct clause is needed for the rollup tables in order to handle corner case where
        // JVM termination occurs in between rollup and updating gauge_point_last_rollup_times
        // in which case a duplicate entry will occur after the next startup
        return dataSource.query("select distinct capture_time, value from " + tableName
                + " where gauge_meta_id = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new GaugePointRowMapper(gaugeName), gaugeMetaId,
                captureTimeFrom, captureTimeTo);
    }

    public int getRollupLevelForView(long from, long to) {
        long millis = to - from;
        if (millis >= rollup2ViewThresholdMillis) {
            return 2;
        } else if (millis >= rollup1ViewThresholdMillis) {
            return 1;
        } else {
            return 0;
        }
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table gauge_point_rollup_0");
        dataSource.execute("truncate table gauge_point_rollup_1");
        dataSource.execute("truncate table gauge_point_rollup_2");
    }

    void deleteBefore(long captureTime, @Untainted int rollupLevel) throws SQLException {
        dataSource.deleteBefore("gauge_point_rollup_" + rollupLevel, captureTime);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, long fixedRollupMillis,
            @Untainted int toRollupLevel, @Untainted int fromRollupLevel) throws SQLException {
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil(capture_time / " + fixedRollupMillis + ".0) * "
                + fixedRollupMillis);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, false, toRollupLevel,
                fromRollupLevel);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, true, toRollupLevel,
                fromRollupLevel);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, @Untainted String captureTimeSql,
            boolean everIncreasing, @Untainted int toRollupLevel, @Untainted int fromRollupLevel)
                    throws SQLException {
        String aggregateFunction = everIncreasing ? "max" : "avg";
        dataSource.update("insert into gauge_point_rollup_" + toRollupLevel
                + " (gauge_meta_id, capture_time, value, count) select gauge_meta_id, "
                + captureTimeSql + " ceil_capture_time, " + aggregateFunction
                + "(value), count(*) from gauge_point_rollup_" + fromRollupLevel
                + " gp, gauge_meta gm where gp.capture_time > ? and gp.capture_time <= ?"
                + " and gp.gauge_meta_id = gm.id and gm.ever_increasing = ?"
                + " group by gp.gauge_meta_id, ceil_capture_time", lastRollupTime, safeRollupTime,
                everIncreasing);
    }

    private static class LastRollupTimesExtractor
            implements ResultSetExtractor<Long/*@Nullable*/[]> {
        @Override
        public Long/*@Nullable*/[] extractData(ResultSet resultSet) throws Exception {
            if (resultSet.next()) {
                return new Long[] {resultSet.getLong(1), resultSet.getLong(2)};
            }
            return null;
        }
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
