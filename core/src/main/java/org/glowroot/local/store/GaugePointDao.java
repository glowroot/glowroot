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
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLongArray;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.collector.GaugePoint;
import org.glowroot.collector.GaugePointRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.config.RollupConfig;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common.Checkers.castUntainted;

public class GaugePointDao implements GaugePointRepository {

    private static final ImmutableList<Column> gaugePointRollup0Columns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE));

    private static final ImmutableList<Column> gaugePointRollupColumns = ImmutableList.<Column>of(
            Column.of("gauge_meta_id", Types.BIGINT),
            Column.of("capture_time", Types.BIGINT),
            Column.of("value", Types.DOUBLE),
            Column.of("count", Types.DOUBLE)); // count is needed for further rollups

    private static final ImmutableList<Index> gaugePointRollup0Indexes =
            ImmutableList.<Index>of(Index.of("gauge_point_rollup_0_idx",
                    ImmutableList.of("gauge_meta_id", "capture_time", "value")));

    private final GaugeMetaDao gaugeMetaDao;
    private final DataSource dataSource;
    private final ConfigService configService;
    private final Clock clock;
    private final ImmutableList<RollupConfig> rollupConfigs;

    // AtomicLongArray used for visibility
    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    GaugePointDao(DataSource dataSource, ConfigService configService, Clock clock)
            throws SQLException {
        gaugeMetaDao = new GaugeMetaDao(dataSource);
        this.dataSource = dataSource;
        this.configService = configService;
        this.clock = clock;
        this.rollupConfigs = configService.getRollupConfigs();

        dataSource.syncTable("gauge_point_rollup_0", gaugePointRollup0Columns);
        dataSource.syncIndexes("gauge_point_rollup_0", gaugePointRollup0Indexes);
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            dataSource.syncTable("gauge_point_rollup_" + castUntainted(i), gaugePointRollupColumns);
            dataSource.syncIndexes("gauge_point_rollup_" + castUntainted(i), ImmutableList.of(
                    Index.of("gauge_point_rollup_" + castUntainted(i) + "_idx",
                            ImmutableList.of("gauge_meta_id", "capture_time", "value"))));
        }
        List<Column> columns = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columns.add(Column.of("last_rollup_" + i + "_time", Types.BIGINT));
        }
        dataSource.syncTable("gauge_point_last_rollup_times", columns);

        List<String> columnNames = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columnNames.add("last_rollup_" + i + "_time");
        }
        Joiner joiner = Joiner.on(", ");
        String selectClause = castUntainted(joiner.join(columnNames));
        long[] lastRollupTimes =
                dataSource.query("select " + selectClause + " from gauge_point_last_rollup_times",
                        new LastRollupTimesExtractor());
        if (lastRollupTimes == null) {
            long[] values = new long[rollupConfigs.size()];
            String valueClause = castUntainted(joiner.join(Longs.asList(values)));
            dataSource.update("insert into gauge_point_last_rollup_times (" + selectClause
                    + ") values (" + valueClause + ")");
            this.lastRollupTimes = new AtomicLongArray(values);
        } else {
            this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);
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
            long safeCurrentTime = clock.currentTimeMillis() - 1;
            for (int i = 0; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long safeRollupTime = AggregateDao.getSafeRollupTime(safeCurrentTime,
                        intervalMillis);
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, intervalMillis, i + 1, i);
                    // JVM termination here will cause last_rollup_*_time to be out of sync, which
                    // will cause a re-rollup of this time after the next startup, but this possible
                    // duplicate is filtered out by the distinct clause in readGaugePoints()
                    dataSource.update("update gauge_point_last_rollup_times set last_rollup_"
                            + castUntainted(i + 1) + "_time = ?", safeRollupTime);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    public ImmutableList<GaugePoint> readGaugePoints(String gaugeName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws SQLException {
        String tableName = "gauge_point_rollup_" + castUntainted(rollupLevel);
        GaugeMeta gaugeMeta = gaugeMetaDao.getGaugeMetaId(gaugeName);
        if (gaugeMeta == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        // the distinct clause is needed for the rollup tables in order to handle corner case where
        // JVM termination occurs in between rollup and updating gauge_point_last_rollup_times
        // in which case a duplicate entry will occur after the next startup
        return dataSource.query("select distinct capture_time, value from " + tableName
                + " where gauge_meta_id = ? and capture_time >= ? and capture_time <= ?"
                + " order by capture_time", new GaugePointRowMapper(gaugeName), gaugeMeta.id(),
                captureTimeFrom, captureTimeTo);
    }

    public List<GaugePoint> readManuallyRolledUpGaugePoints(long from, long to, String gaugeName,
            int rollupLevel, long liveCaptureTime) throws SQLException {
        long fixedIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        GaugeMeta gaugeMeta = gaugeMetaDao.getGaugeMetaId(gaugeName);
        if (gaugeMeta == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        String aggregateFunction = gaugeMeta.everIncreasing() ? "max" : "avg";
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        ImmutableList<GaugePoint> gaugePoints = dataSource.query("select " + captureTimeSql
                + " ceil_capture_time, " + aggregateFunction + "(value) from gauge_point_rollup_0"
                + " where gauge_meta_id = ? and capture_time > ? and capture_time <= ?"
                + " group by ceil_capture_time order by ceil_capture_time",
                new GaugePointRowMapper(gaugeName), gaugeMeta.id(), from, to);
        if (gaugePoints.isEmpty()) {
            return ImmutableList.of();
        }
        GaugePoint lastGaugePoint = gaugePoints.get(gaugePoints.size() - 1);
        if (lastGaugePoint.captureTime() <= liveCaptureTime) {
            return gaugePoints;
        }
        List<GaugePoint> correctedGaugePoints = Lists.newArrayList(gaugePoints);
        correctedGaugePoints.set(correctedGaugePoints.size() - 1,
                lastGaugePoint.withCaptureTime(liveCaptureTime));
        return correctedGaugePoints;
    }

    public int getRollupLevelForView(long from, long to) {
        long millis = to - from;
        long timeAgoMillis = clock.currentTimeMillis() - from;
        ImmutableList<Integer> rollupExpirationHours =
                configService.getStorageConfig().rollupExpirationHours();
        // gauge point rollup level 0 shares rollup level 1's expiration
        if (millis < rollupConfigs.get(0).viewThresholdMillis()
                && HOURS.toMillis(rollupExpirationHours.get(0)) > timeAgoMillis) {
            return 0;
        }
        for (int i = 0; i < rollupConfigs.size() - 1; i++) {
            if (millis < rollupConfigs.get(i + 1).viewThresholdMillis()
                    && HOURS.toMillis(rollupExpirationHours.get(i)) > timeAgoMillis) {
                return i + 1;
            }
        }
        return rollupConfigs.size();
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table gauge_point_rollup_0");
        for (int i = 1; i <= configService.getRollupConfigs().size(); i++) {
            dataSource.execute("truncate table gauge_point_rollup_" + castUntainted(i));
        }
    }

    void deleteBefore(long captureTime, int rollupLevel) throws SQLException {
        dataSource.deleteBefore("gauge_point_rollup_" + castUntainted(rollupLevel), captureTime);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws SQLException {
        // TODO handle when offset is different for lastRollupTime and safeRollupTime?
        int offsetMillis = TimeZone.getDefault().getOffset(safeRollupTime);
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil((capture_time + " + offsetMillis + ") / "
                + fixedIntervalMillis + ".0) * " + fixedIntervalMillis + " - " + offsetMillis);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, false, toRollupLevel,
                fromRollupLevel);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, true, toRollupLevel,
                fromRollupLevel);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, @Untainted String captureTimeSql,
            boolean everIncreasing, int toRollupLevel, int fromRollupLevel) throws SQLException {
        String aggregateFunction = everIncreasing ? "max" : "avg";
        dataSource.update("insert into gauge_point_rollup_" + castUntainted(toRollupLevel)
                + " (gauge_meta_id, capture_time, value, count) select gauge_meta_id, "
                + captureTimeSql + " ceil_capture_time, " + aggregateFunction
                + "(value), count(*) from gauge_point_rollup_" + castUntainted(fromRollupLevel)
                + " gp, gauge_meta gm where gp.capture_time > ? and gp.capture_time <= ?"
                + " and gp.gauge_meta_id = gm.id and gm.ever_increasing = ?"
                + " group by gp.gauge_meta_id, ceil_capture_time", lastRollupTime, safeRollupTime,
                everIncreasing);
    }

    private static class LastRollupTimesExtractor
            implements ResultSetExtractor<long/*@Nullable*/[]> {
        @Override
        public long/*@Nullable*/[] extractData(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return null;
            }
            int columns = resultSet.getMetaData().getColumnCount();
            long[] values = new long[columns];
            for (int i = 0; i < columns; i++) {
                values[i] = resultSet.getLong(i + 1);
            }
            return values;
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
