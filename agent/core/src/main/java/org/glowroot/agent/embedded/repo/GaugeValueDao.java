/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.embedded.util.DataSource.JdbcUpdate;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.ImmutableIndex;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;
import org.glowroot.agent.embedded.util.Schemas.Index;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.util.Gauges;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.glowroot.agent.util.Checkers.castUntainted;

public class GaugeValueDao implements GaugeValueRepository {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("value", ColumnType.DOUBLE),
            // weight is needed for rollups
            // for non-counters, it is the number of recording that the (averaged) value represents
            // for counters, it is the interval of time that the (averaged) value represents
            ImmutableColumn.of("weight", ColumnType.BIGINT));

    private final GaugeIdDao gaugeIdDao;
    private final GaugeNameDao gaugeNameDao;
    private final DataSource dataSource;
    private final Clock clock;
    private final ImmutableList<RollupConfig> rollupConfigs;

    // AtomicLongArray used for visibility
    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    GaugeValueDao(DataSource dataSource, GaugeIdDao gaugeIdDao, GaugeNameDao gaugeNameDao,
            Clock clock) throws Exception {
        this.dataSource = dataSource;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeNameDao = gaugeNameDao;
        this.clock = clock;
        this.rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());

        for (int i = 0; i <= rollupConfigs.size(); i++) {
            dataSource.syncTable("gauge_value_rollup_" + castUntainted(i), columns);
            dataSource.syncIndexes("gauge_value_rollup_" + castUntainted(i),
                    ImmutableList.<Index>of(
                            ImmutableIndex.of(
                                    "gauge_value_rollup_" + castUntainted(i) + "_idx",
                                    ImmutableList.of("gauge_id", "capture_time", "value",
                                            "weight")),
                            // this index is used by rollup query
                            ImmutableIndex.of(
                                    "gauge_value_rollup_" + castUntainted(i)
                                            + "_by_capture_time_idx",
                                    ImmutableList.of("capture_time", "gauge_id", "value",
                                            "weight"))));
        }
        List<Column> columns = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columns.add(ImmutableColumn.of("last_rollup_" + i + "_time", ColumnType.BIGINT));
        }
        dataSource.syncTable("gauge_value_last_rollup_times", columns);

        lastRollupTimes = initData(rollupConfigs, dataSource);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public List<Gauge> getRecentlyActiveGauges(String agentRollupId) throws Exception {
        long now = clock.currentTimeMillis();
        long from = now - DAYS.toMillis(7);
        return getGauges(agentRollupId, from, now + DAYS.toMillis(365));
    }

    @Override
    public List<Gauge> getGauges(String agentRollupId, long from, long to) throws Exception {
        Set<String> allGaugeNames = gaugeNameDao.readAllGaugeNames(from, to);
        List<Gauge> gauges = Lists.newArrayList();
        for (String gaugeName : allGaugeNames) {
            gauges.add(Gauges.getGauge(gaugeName));
        }
        return gauges;
    }

    public void store(List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        Map<GaugeValue, Long> gaugeValueIdMap = Maps.newLinkedHashMap();
        for (GaugeValue gaugeValue : gaugeValues) {
            long gaugeId = gaugeIdDao.updateLastCaptureTime(gaugeValue.getGaugeName(),
                    gaugeValue.getCaptureTime());
            if (gaugeId == -1) {
                // data source is closing and a new gauge id was needed, but could not insert it
                // --or-- race condition with GaugeIdDao.deleteAll() in which case return is good
                // option also
                return;
            }
            gaugeNameDao.insert(gaugeValue.getCaptureTime(), gaugeValue.getGaugeName());
            gaugeValueIdMap.put(gaugeValue, gaugeId);
        }
        dataSource.batchUpdate(new GaugeValuesBinder(gaugeValueIdMap));
        synchronized (rollupLock) {
            // clock can never go backwards and future gauge captures will wait until this method
            // completes since ScheduledExecutorService.scheduleAtFixedRate() guarantees that future
            // invocations of GaugeCollector will wait until prior invocations complete
            long safeCurrentTime = clock.currentTimeMillis() - 1;
            for (int i = 0; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long safeRollupTime =
                        RollupLevelService.getSafeRollupTime(safeCurrentTime, intervalMillis);
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, intervalMillis, i + 1, i);
                    // JVM termination here will cause last_rollup_*_time to be out of sync, which
                    // will cause a re-rollup of this time after the next startup, but this is ok
                    // since it will just overwrite prior rollup
                    dataSource.update("update gauge_value_last_rollup_times set last_rollup_"
                            + castUntainted(i + 1) + "_time = ?", safeRollupTime);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // from is INCLUSIVE
    @Override
    public List<GaugeValue> readGaugeValues(String agentRollupId, String gaugeName, long from,
            long to, int rollupLevel) throws Exception {
        Long gaugeId = gaugeIdDao.getGaugeId(gaugeName);
        if (gaugeId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        return dataSource.query(new GaugeValueQuery(gaugeId, from, to, rollupLevel));
    }

    void deleteBefore(long captureTime, int rollupLevel) throws Exception {
        dataSource.deleteBefore("gauge_value_rollup_" + castUntainted(rollupLevel), captureTime);
    }

    void reinitAfterDeletingDatabase() throws Exception {
        AtomicLongArray lastRollupTimes = initData(rollupConfigs, dataSource);
        for (int i = 0; i < lastRollupTimes.length(); i++) {
            this.lastRollupTimes.set(i, lastRollupTimes.get(i));
        }
    }

    private void rollup(long lastRollupTime, long safeRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        dataSource.update("merge into gauge_value_rollup_" + castUntainted(toRollupLevel)
                + " (gauge_id, capture_time, value, weight) key (gauge_id, capture_time)"
                + " select gauge_id, " + captureTimeSql + " ceil_capture_time,"
                + " sum(value * weight) / sum(weight), sum(weight) from gauge_value_rollup_"
                + castUntainted(fromRollupLevel) + " gp where gp.capture_time > ?"
                + " and gp.capture_time <= ? group by gp.gauge_id, ceil_capture_time",
                lastRollupTime, safeRollupTime);
    }

    private static AtomicLongArray initData(ImmutableList<RollupConfig> rollupConfigs,
            DataSource dataSource) throws Exception {
        List<String> columnNames = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columnNames.add("last_rollup_" + i + "_time");
        }
        Joiner joiner = Joiner.on(", ");
        String selectClause = castUntainted(joiner.join(columnNames));
        long[] lastRollupTimes = dataSource.query(new LastRollupTimesQuery(selectClause));
        if (lastRollupTimes.length == 0) {
            long[] values = new long[rollupConfigs.size()];
            String valueClause = castUntainted(joiner.join(Longs.asList(values)));
            dataSource.update("insert into gauge_value_last_rollup_times (" + selectClause
                    + ") values (" + valueClause + ")");
            return new AtomicLongArray(values);
        } else {
            return new AtomicLongArray(lastRollupTimes);
        }
    }

    private class GaugeValuesBinder implements JdbcUpdate {

        private final Map<GaugeValue, Long> gaugeValueIdMap;

        private GaugeValuesBinder(Map<GaugeValue, Long> gaugeValueIdMap) {
            this.gaugeValueIdMap = gaugeValueIdMap;
        }

        @Override
        public @Untainted String getSql() {
            return "insert into gauge_value_rollup_0 (gauge_id, capture_time, value, weight)"
                    + " values (?, ?, ?, ?)";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            for (Entry<GaugeValue, Long> entry : gaugeValueIdMap.entrySet()) {
                GaugeValue gaugeValue = entry.getKey();
                long gaugeId = entry.getValue();
                int i = 1;
                preparedStatement.setLong(i++, gaugeId);
                preparedStatement.setLong(i++, gaugeValue.getCaptureTime());
                preparedStatement.setDouble(i++, gaugeValue.getValue());
                preparedStatement.setLong(i++, gaugeValue.getWeight());
                preparedStatement.addBatch();
            }
        }
    }

    private static class LastRollupTimesQuery implements JdbcQuery<long[]> {

        private final @Untainted String selectClause;

        public LastRollupTimesQuery(@Untainted String selectClause) {
            this.selectClause = selectClause;
        }

        @Override
        public @Untainted String getSql() {
            return "select " + selectClause + " from gauge_value_last_rollup_times";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {}

        @Override
        public long[] processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                return new long[0];
            }
            int columns = resultSet.getMetaData().getColumnCount();
            long[] values = new long[columns];
            for (int i = 0; i < columns; i++) {
                values[i] = resultSet.getLong(i + 1);
            }
            return values;
        }

        @Override
        public long[] valueIfDataSourceClosed() {
            return new long[0];
        }
    }

    private class GaugeValueQuery implements JdbcRowQuery<GaugeValue> {

        private final long gaugeId;
        private final long from;
        private final long to;
        private final int rollupLevel;

        private GaugeValueQuery(long gaugeId, long from, long to, int rollupLevel) {
            this.gaugeId = gaugeId;
            this.from = from;
            this.to = to;
            this.rollupLevel = rollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select capture_time, value, weight from gauge_value_rollup_"
                    + castUntainted(rollupLevel) + " where gauge_id = ? and capture_time >= ?"
                    + " and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setLong(i++, gaugeId);
            preparedStatement.setLong(i++, from);
            preparedStatement.setLong(i++, to);
        }

        @Override
        public GaugeValue mapRow(ResultSet resultSet) throws SQLException {
            int i = 1;
            return GaugeValue.newBuilder()
                    .setCaptureTime(resultSet.getLong(i++))
                    .setValue(resultSet.getDouble(i++))
                    .setWeight(resultSet.getLong(i++))
                    .build();
        }
    }
}
