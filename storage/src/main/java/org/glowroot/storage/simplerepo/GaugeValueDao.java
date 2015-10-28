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
package org.glowroot.storage.simplerepo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.GaugeConfig.MBeanAttribute;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Patterns;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.ImmutableGauge;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.DataSource.RowMapper;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;

import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class GaugeValueDao implements GaugeValueRepository {

    private static final ImmutableList<Column> gaugeValueRollupColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("gauge_id", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("value", ColumnType.DOUBLE),
            // weight is needed for rollups
            // for non-counters, it is the number of recording that the (averaged) value represents
            // for counters, it is the interval of time that the (averaged) value represents
            ImmutableColumn.of("weight", ColumnType.BIGINT));

    private static final ImmutableList<Index> gaugeValueRollup0Indexes =
            ImmutableList.<Index>of(ImmutableIndex.of("gauge_value_rollup_0_idx",
                    ImmutableList.of("gauge_id", "capture_time", "value")));

    private final GaugeMetaDao gaugeMetaDao;
    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final ImmutableList<RollupConfig> rollupConfigs;

    // AtomicLongArray used for visibility
    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    GaugeValueDao(DataSource dataSource, ConfigRepository configRepository, Clock clock)
            throws Exception {
        gaugeMetaDao = new GaugeMetaDao(dataSource);
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.rollupConfigs = configRepository.getRollupConfigs();

        Schema schema = dataSource.getSchema();
        schema.syncTable("gauge_value_rollup_0", gaugeValueRollupColumns);
        schema.syncIndexes("gauge_value_rollup_0", gaugeValueRollup0Indexes);
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            schema.syncTable("gauge_value_rollup_" + castUntainted(i), gaugeValueRollupColumns);
            schema.syncIndexes("gauge_value_rollup_" + castUntainted(i),
                    ImmutableList.<Index>of(
                            ImmutableIndex.of("gauge_value_rollup_" + castUntainted(i) + "_idx",
                                    ImmutableList.of("gauge_id", "capture_time", "value"))));
        }
        List<Column> columns = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columns.add(ImmutableColumn.of("last_rollup_" + i + "_time", ColumnType.BIGINT));
        }
        schema.syncTable("gauge_value_last_rollup_times", columns);

        List<String> columnNames = Lists.newArrayList();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            columnNames.add("last_rollup_" + i + "_time");
        }
        Joiner joiner = Joiner.on(", ");
        String selectClause = castUntainted(joiner.join(columnNames));
        long[] lastRollupTimes =
                dataSource.query("select " + selectClause + " from gauge_value_last_rollup_times",
                        new LastRollupTimesExtractor());
        if (lastRollupTimes == null) {
            long[] values = new long[rollupConfigs.size()];
            String valueClause = castUntainted(joiner.join(Longs.asList(values)));
            dataSource.update("insert into gauge_value_last_rollup_times (" + selectClause
                    + ") values (" + valueClause + ")");
            this.lastRollupTimes = new AtomicLongArray(values);
        } else {
            this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);
        }

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public List<Gauge> getGauges(String serverGroup) throws Exception {
        List<String> allGaugeNames = gaugeMetaDao.readAllGaugeNames();
        List<Gauge> gauges = Lists.newArrayList();
        for (GaugeConfig gaugeConfig : configRepository.getGaugeConfigs(serverGroup)) {
            for (MBeanAttribute mbeanAttribute : gaugeConfig.mbeanAttributes()) {
                String gaugeName = gaugeConfig.mbeanObjectName() + ':' + mbeanAttribute.name();
                if (mbeanAttribute.counter()) {
                    gaugeName += "[counter]";
                }
                if (gaugeName.contains("*")) {
                    gauges.addAll(getMatching(gaugeName, mbeanAttribute, allGaugeNames));
                } else {
                    String display = GaugeConfig.display(gaugeConfig.mbeanObjectName()) + '/'
                            + mbeanAttribute.name();
                    gauges.add(ImmutableGauge.of(gaugeName, display, mbeanAttribute.counter()));
                }
            }
        }
        return gauges;
    }

    @Override
    public void store(final String serverGroup, final List<GaugeValue> gaugeValues)
            throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        dataSource.batchUpdate("insert into gauge_value_rollup_0 (gauge_id, capture_time,"
                + " value, weight) values (?, ?, ?, ?)", new PreparedStatementBinder() {
                    @Override
                    public void bind(PreparedStatement preparedStatement) throws Exception {
                        for (GaugeValue gaugeValue : gaugeValues) {
                            long gaugeId = gaugeMetaDao.getOrCreateGaugeId(serverGroup,
                                    gaugeValue.getGaugeName());
                            if (gaugeId == -1) {
                                // data source is closing and a new gauge id was needed, but could
                                // not insert it, but this bind is already inside of the data source
                                // lock so any inserts here will succeed, thus the break
                                break;
                            }
                            preparedStatement.setLong(1, gaugeId);
                            preparedStatement.setLong(2, gaugeValue.getCaptureTime());
                            preparedStatement.setDouble(3, gaugeValue.getValue());
                            long weight = gaugeValue.getIntervalNanos();
                            if (weight == 0) {
                                // this is for non-counter gauges
                                weight = 1;
                            }
                            preparedStatement.setLong(4, weight);
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
                long safeRollupTime =
                        AggregateDao.getSafeRollupTime(safeCurrentTime, intervalMillis);
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, intervalMillis, i + 1, i);
                    // JVM termination here will cause last_rollup_*_time to be out of sync, which
                    // will cause a re-rollup of this time after the next startup, but this possible
                    // duplicate is filtered out by the distinct clause in readGaugeValues()
                    dataSource.update("update gauge_value_last_rollup_times set last_rollup_"
                            + castUntainted(i + 1) + "_time = ?", safeRollupTime);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    @Override
    public List<GaugeValue> readGaugeValues(String serverGroup, String gaugeName,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception {
        String tableName = "gauge_value_rollup_" + castUntainted(rollupLevel);
        Long gaugeId = gaugeMetaDao.getGaugeId(serverGroup, gaugeName);
        if (gaugeId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        // the distinct clause is needed for the rollup tables in order to handle corner case where
        // JVM termination occurs in between rollup and updating gauge_value_last_rollup_times
        // in which case a duplicate entry will occur after the next startup
        return dataSource.query(
                "select distinct capture_time, value from " + tableName + " where gauge_id = ?"
                        + " and capture_time >= ? and capture_time <= ? order by capture_time",
                new GaugeValueRowMapper(), gaugeId, captureTimeFrom, captureTimeTo);
    }

    @Override
    public List<GaugeValue> readManuallyRolledUpGaugeValues(String serverGroup, long from, long to,
            String gaugeName, int rollupLevel, long liveCaptureTime) throws Exception {
        long fixedIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        Long gaugeId = gaugeMetaDao.getGaugeId(serverGroup, gaugeName);
        if (gaugeId == null) {
            // not necessarily an error, gauge id not created until first store
            return ImmutableList.of();
        }
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        List<GaugeValue> gaugeValues = dataSource.query(
                "select " + captureTimeSql + " ceil_capture_time, avg(value)"
                        + " from gauge_value_rollup_0 where gauge_id = ? and capture_time > ?"
                        + " and capture_time <= ? group by ceil_capture_time"
                        + " order by ceil_capture_time",
                new GaugeValueRowMapper(), gaugeId, from, to);
        if (gaugeValues.isEmpty()) {
            return ImmutableList.of();
        }
        GaugeValue lastGaugeValue = gaugeValues.get(gaugeValues.size() - 1);
        if (lastGaugeValue.getCaptureTime() <= liveCaptureTime) {
            return gaugeValues;
        }
        List<GaugeValue> correctedGaugeValues = Lists.newArrayList(gaugeValues);
        GaugeValue correctedLastGaugeValue = GaugeValue.newBuilder()
                .setCaptureTime(liveCaptureTime)
                .setValue(lastGaugeValue.getValue())
                .build();
        correctedGaugeValues.set(correctedGaugeValues.size() - 1, correctedLastGaugeValue);
        return correctedGaugeValues;
    }

    @Override
    public int getRollupLevelForView(String serverGroup, long from, long to) {
        long millis = to - from;
        long timeAgoMillis = clock.currentTimeMillis() - from;
        ImmutableList<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
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

    @Override
    public void deleteAll(String serverGroup) throws Exception {
        String whereClause = "gauge_id in (select gauge_id from gauge_meta where server_group = ?)";
        dataSource.batchDelete("gauge_value_rollup_0", whereClause, serverGroup);
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            dataSource.batchDelete("gauge_value_rollup_" + castUntainted(i), whereClause,
                    serverGroup);
        }
        gaugeMetaDao.deleteAll(serverGroup);
    }

    void deleteBefore(String serverGroup, long captureTime, int rollupLevel) throws Exception {
        String whereClause = "gauge_id in (select gauge_id from gauge_meta where server_group = ?)"
                + " and capture_time < ?";
        dataSource.batchDelete("gauge_value_rollup_" + castUntainted(rollupLevel), whereClause,
                serverGroup, captureTime);
    }

    private List<Gauge> getMatching(String gaugeName, MBeanAttribute mbeanAttribute,
            List<String> allGaugeNames) {
        List<Gauge> gauges = Lists.newArrayList();
        Pattern pattern = Pattern.compile(Patterns.buildSimplePattern(gaugeName));
        for (String storedGaugeName : allGaugeNames) {
            if (pattern.matcher(storedGaugeName).matches()) {
                int index = storedGaugeName.lastIndexOf(':');
                String objectName = storedGaugeName.substring(0, index);
                String display = GaugeConfig.display(objectName) + '/' + mbeanAttribute.name();
                gauges.add(ImmutableGauge.of(storedGaugeName, display, mbeanAttribute.counter()));
            }
        }
        return gauges;
    }

    private void rollup(long lastRollupTime, long safeRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        // TODO handle when offset is different for lastRollupTime and safeRollupTime?
        int offsetMillis = TimeZone.getDefault().getOffset(safeRollupTime);
        // need ".0" to force double result
        String captureTimeSql = castUntainted("ceil((capture_time + " + offsetMillis + ") / "
                + fixedIntervalMillis + ".0) * " + fixedIntervalMillis + " - " + offsetMillis);
        rollup(lastRollupTime, safeRollupTime, captureTimeSql, toRollupLevel, fromRollupLevel);
    }

    private void rollup(long lastRollupTime, long safeRollupTime, @Untainted String captureTimeSql,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        dataSource.update("insert into gauge_value_rollup_" + castUntainted(toRollupLevel)
                + " (gauge_id, capture_time, value, weight) select gauge_id, " + captureTimeSql
                + " ceil_capture_time, sum(value * weight) / sum(weight), sum(weight)"
                + " from gauge_value_rollup_" + castUntainted(fromRollupLevel)
                + " gp where gp.capture_time > ? and gp.capture_time <= ?"
                + " group by gp.gauge_id, ceil_capture_time", lastRollupTime, safeRollupTime);
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

    private static class GaugeValueRowMapper implements RowMapper<GaugeValue> {
        @Override
        public GaugeValue mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            double value = resultSet.getDouble(2);
            return GaugeValue.newBuilder()
                    .setCaptureTime(captureTime)
                    .setValue(value)
                    .build();
        }
    }
}
