/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.central.storage;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.ServerRepository.ServerRollup;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.central.util.Checkers.castUntainted;

public class GaugeValueDao implements GaugeValueRepository {

    private final Session session;
    private final ServerDao serverDao;
    private final ConfigRepository configRepository;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> readValuePS;
    private final ImmutableList<PreparedStatement> readValueForRollupPS;

    private final ImmutableList<PreparedStatement> insertNamePS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final AtomicBoolean rollup = new AtomicBoolean();

    public GaugeValueDao(Session session, ServerDao serverDao, ConfigRepository configRepository) {
        this.session = session;
        this.serverDao = serverDao;
        this.configRepository = configRepository;

        int count = configRepository.getRollupConfigs().size();

        List<PreparedStatement> insertValuePS = Lists.newArrayList();
        List<PreparedStatement> readValuePS = Lists.newArrayList();
        List<PreparedStatement> readValueForRollupPS = Lists.newArrayList();
        for (int i = 0; i <= count; i++) {
            // name already has "[counter]" suffix when it is a counter
            session.execute("create table if not exists gauge_value_rollup_" + castUntainted(i)
                    + " (server_rollup varchar, gauge_name varchar, capture_time timestamp,"
                    + " value double, weight bigint, primary key ((server_rollup, gauge_name),"
                    + " capture_time))");
            insertValuePS.add(session.prepare("insert into gauge_value_rollup_" + castUntainted(i)
                    + " (server_rollup, gauge_name, capture_time, value, weight)"
                    + " values (?, ?, ?, ?, ?)"));
            readValuePS.add(session.prepare("select capture_time, value, weight from"
                    + " gauge_value_rollup_" + castUntainted(i) + " where server_rollup = ?"
                    + " and gauge_name = ? and capture_time >= ? and capture_time <= ?"));
            readValueForRollupPS.add(session.prepare("select value, weight from gauge_value_rollup_"
                    + castUntainted(i) + " where server_rollup = ? and gauge_name = ?"
                    + " and capture_time > ? and capture_time <= ?"));
        }
        this.insertValuePS = ImmutableList.copyOf(insertValuePS);
        this.readValuePS = ImmutableList.copyOf(readValuePS);
        this.readValueForRollupPS = ImmutableList.copyOf(readValueForRollupPS);

        // TTL on gauge_name table needs to be max(TTL) of gauge_value_rollup_*
        session.execute("create table if not exists gauge_name (server_rollup varchar,"
                + " gauge_name varchar, primary key (server_rollup, gauge_name))");

        List<PreparedStatement> insertNamePS = Lists.newArrayList();
        for (int i = 0; i < 1; i++) {
            insertNamePS.add(session
                    .prepare("insert into gauge_name (server_rollup, gauge_name) values (?, ?)"));
        }
        this.insertNamePS = ImmutableList.copyOf(insertNamePS);

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i <= count; i++) {
            session.execute("create table if not exists gauge_needs_rollup_" + i
                    + " (server_rollup varchar, capture_time timestamp, gauge_name varchar,"
                    + " last_update timeuuid, primary key (server_rollup, capture_time,"
                    + " gauge_name))");
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (server_rollup, capture_time, gauge_name, last_update) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select capture_time, gauge_name, last_update from"
                    + " gauge_needs_rollup_" + i + " where server_rollup = ?"
                    + " and capture_time <= ?"));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i
                    + " where server_rollup = ? and capture_time = ? and gauge_name = ?"
                    + " if last_update = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    @Override
    public void store(String serverId, List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        BatchStatement batchStatement = new BatchStatement();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, gaugeValue.getGaugeName());
            boundStatement.setTimestamp(2, new Date(gaugeValue.getCaptureTime()));
            boundStatement.setDouble(3, gaugeValue.getValue());
            boundStatement.setLong(4, gaugeValue.getWeight());
            batchStatement.add(boundStatement);

            boundStatement = insertNamePS.get(0).bind();
            boundStatement.setString(0, serverId);
            boundStatement.setString(1, gaugeValue.getGaugeName());
            batchStatement.add(boundStatement);
        }
        session.execute(batchStatement);
        Map<String, Long> maxCaptureTimes = Maps.newHashMap();
        for (GaugeValue gaugeValue : gaugeValues) {
            Long maxCaptureTime = maxCaptureTimes.get(gaugeValue.getGaugeName());
            if (maxCaptureTime == null) {
                maxCaptureTimes.put(gaugeValue.getGaugeName(), gaugeValue.getCaptureTime());
            } else {
                maxCaptureTimes.put(gaugeValue.getGaugeName(),
                        Math.max(maxCaptureTime, gaugeValue.getCaptureTime()));
            }
        }
        batchStatement = new BatchStatement();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (Entry<String, Long> entry : maxCaptureTimes.entrySet()) {
            String gaugeName = entry.getKey();
            long captureTime = entry.getValue();
            for (int i = 1; i <= rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i - 1).intervalMillis();
                long rollupCaptureTime =
                        (long) Math.ceil(captureTime / (double) intervalMillis) * intervalMillis;
                BoundStatement boundStatement = insertNeedsRollup.get(i - 1).bind();
                boundStatement.setString(0, serverId);
                boundStatement.setTimestamp(1, new Date(rollupCaptureTime));
                boundStatement.setString(2, gaugeName);
                boundStatement.setUUID(3, UUIDs.timeBased());
                batchStatement.add(boundStatement);
            }
        }
        session.execute(batchStatement);
        serverDao.updateLastCaptureTime(serverId, true);
        if (!rollup.getAndSet(true)) {
            try {
                // TODO submit checker framework issue
                @SuppressWarnings("assignment.type.incompatible")
                long overallMaxCaptureTime =
                        maxCaptureTimes.values().stream().max(Long::compareTo).orElse(0L);
                rollup(overallMaxCaptureTime - 60000);
            } finally {
                rollup.set(false);
            }
        }
    }

    @Override
    public List<Gauge> getGauges(String serverRollup) {
        ResultSet results = session
                .execute("select gauge_name from gauge_name where server_rollup = ?", serverRollup);
        List<Gauge> gauges = Lists.newArrayList();
        for (Row row : results) {
            gauges.add(Gauges.getGauge(checkNotNull(row.getString(0))));
        }
        return gauges;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<GaugeValue> readGaugeValues(String serverRollup, String gaugeName,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        BoundStatement boundStatement = readValuePS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, gaugeName);
        boundStatement.setTimestamp(2, new Date(captureTimeFrom));
        boundStatement.setTimestamp(3, new Date(captureTimeTo));
        ResultSet results = session.execute(boundStatement);
        List<GaugeValue> gaugeValues = Lists.newArrayList();
        for (Row row : results) {
            gaugeValues.add(GaugeValue.newBuilder()
                    .setCaptureTime(checkNotNull(row.getTimestamp(0)).getTime())
                    .setValue(row.getDouble(1))
                    .setWeight(row.getLong(2))
                    .build());
        }
        return gaugeValues;
    }

    @Override
    public void deleteAll(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    // should be called one minute after to reduce likelihood of data coming in late
    void rollup(long sortOfSafeRollupTime) throws Exception {
        for (ServerRollup serverRollup : serverDao.readServerRollups()) {
            for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
                long intervalMillis =
                        configRepository.getRollupConfigs().get(i - 1).intervalMillis();
                rollup(i, serverRollup.name(),
                        (sortOfSafeRollupTime / intervalMillis) * intervalMillis);
            }
        }
    }

    private void rollup(int rollupLevel, String serverRollup, long sortOfSafeRollupTime)
            throws Exception {
        long rollupIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setTimestamp(1, new Date(sortOfSafeRollupTime));
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String gaugeName = checkNotNull(row.getString(1));
            UUID lastUpdate = row.getUUID(2);
            rollupOne(rollupLevel, serverRollup, gaugeName, captureTime - rollupIntervalMillis,
                    captureTime);
            boundStatement = deleteNeedsRollup.get(rollupLevel - 1).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setTimestamp(1, new Date(captureTime));
            boundStatement.setString(2, gaugeName);
            boundStatement.setUUID(3, lastUpdate);
            session.execute(boundStatement);
        }
    }

    // from is non-inclusive
    private void rollupOne(int rollupLevel, String serverRollup, String gaugeName, long from,
            long to) throws Exception {
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, gaugeName);
        boundStatement.setTimestamp(2, new Date(from));
        boundStatement.setTimestamp(3, new Date(to));
        ResultSet results = session.execute(boundStatement);
        double totalWeightedValue = 0;
        long totalWeight = 0;
        for (Row row : results) {
            double value = row.getDouble(0);
            long weight = row.getLong(1);
            totalWeightedValue += value * weight;
            totalWeight += weight;
        }
        boundStatement = insertValuePS.get(rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, gaugeName);
        boundStatement.setTimestamp(2, new Date(to));
        boundStatement.setDouble(3, totalWeightedValue / totalWeight);
        boundStatement.setLong(4, totalWeight);
        session.execute(boundStatement);
    }

    @OnlyUsedByTests
    void truncateAll() {
        for (int i = 0; i <= configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate gauge_value_rollup_" + castUntainted(i));
        }
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate gauge_needs_rollup_" + castUntainted(i));
        }
        session.execute("truncate gauge_name");
    }
}
