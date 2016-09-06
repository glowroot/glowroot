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
package org.glowroot.server.storage;

import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.server.storage.AggregateDao.NeedsRollup;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class GaugeValueDao implements GaugeValueRepository {

    private static final String WITH_DTCS =
            "with compaction = { 'class' : 'DateTieredCompactionStrategy' }";

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;

    private final GaugeNameDao gaugeNameDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> readValuePS;
    private final ImmutableList<PreparedStatement> readValueForRollupPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    public GaugeValueDao(Session session, ConfigRepository configRepository) {
        this.session = session;
        this.configRepository = configRepository;

        gaugeNameDao = new GaugeNameDao(session, configRepository);

        int count = configRepository.getRollupConfigs().size();

        List<PreparedStatement> insertValuePS = Lists.newArrayList();
        List<PreparedStatement> readValuePS = Lists.newArrayList();
        List<PreparedStatement> readValueForRollupPS = Lists.newArrayList();
        for (int i = 0; i <= count; i++) {
            // name already has "[counter]" suffix when it is a counter
            session.execute("create table if not exists gauge_value_rollup_" + i
                    + " (agent_rollup varchar, gauge_name varchar, capture_time timestamp,"
                    + " value double, weight bigint, primary key ((agent_rollup, gauge_name),"
                    + " capture_time)) " + WITH_DTCS);
            insertValuePS.add(session.prepare("insert into gauge_value_rollup_" + i
                    + " (agent_rollup, gauge_name, capture_time, value, weight)"
                    + " values (?, ?, ?, ?, ?) using ttl ?"));
            readValuePS.add(session.prepare("select capture_time, value, weight from"
                    + " gauge_value_rollup_" + i + " where agent_rollup = ? and gauge_name = ?"
                    + " and capture_time >= ? and capture_time <= ?"));
            readValueForRollupPS.add(session.prepare("select value, weight from gauge_value_rollup_"
                    + i + " where agent_rollup = ? and gauge_name = ? and capture_time > ?"
                    + " and capture_time <= ?"));
        }
        this.insertValuePS = ImmutableList.copyOf(insertValuePS);
        this.readValuePS = ImmutableList.copyOf(readValuePS);
        this.readValueForRollupPS = ImmutableList.copyOf(readValueForRollupPS);

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i <= count; i++) {
            session.execute("create table if not exists gauge_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " gauge_names set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) " + WITH_LCS);
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, gauge_names) values"
                    + " (?, ?, ?, ?)"));
            // limit is just in case rollup falls way behind, to avoid massive memory consumption
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, gauge_names"
                    + " from gauge_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    @Override
    public void store(String agentId, List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        int ttl = getTTLs().get(0);
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            int i = 0;
            boundStatement.setString(i++, agentId);
            String gaugeName = gaugeValue.getGaugeName();
            // TEMPORARY UNTIL ROLL OUT AGENT 0.9.1
            int index = gaugeName.lastIndexOf(':');
            String mbeanObjectName = gaugeName.substring(0, index);
            String mbeanAttributeName = gaugeName.substring(index + 1);
            gaugeName = mbeanObjectName + ':' + mbeanAttributeName.replace('/', '.');
            // END TEMPORARY
            boundStatement.setString(i++, gaugeName);
            long captureTime = gaugeValue.getCaptureTime();
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setDouble(i++, gaugeValue.getValue());
            boundStatement.setLong(i++, gaugeValue.getWeight());
            boundStatement.setInt(i++, getAdjustedTTL(ttl, captureTime));
            futures.add(session.executeAsync(boundStatement));
            gaugeNameDao.maybeUpdateLastCaptureTime(agentId, gaugeName, futures);
        }
        // insert into gauge_needs_rollup_1
        SetMultimap<Long, String> rollupCaptureTimes = getRollupCaptureTimes(gaugeValues);
        for (Entry<Long, Set<String>> entry : Multimaps.asMap(rollupCaptureTimes).entrySet()) {
            BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
            boundStatement.setString(0, agentId);
            boundStatement.setTimestamp(1, new Date(entry.getKey()));
            boundStatement.setUUID(2, UUIDs.timeBased());
            boundStatement.setSet(3, entry.getValue());
            futures.add(session.executeAsync(boundStatement));
        }
        Futures.allAsList(futures).get();
    }

    @Override
    public List<Gauge> getGauges(String agentRollup) {
        List<Gauge> gauges = Lists.newArrayList();
        for (String gaugeName : gaugeNameDao.getGaugeNames(agentRollup)) {
            gauges.add(Gauges.getGauge(gaugeName));
        }
        return gauges;
    }

    // query.from() is INCLUSIVE
    @Override
    public List<GaugeValue> readGaugeValues(String agentRollup, String gaugeName,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        BoundStatement boundStatement = readValuePS.get(rollupLevel).bind();
        boundStatement.setString(0, agentRollup);
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

    public void rollup(String agentRollup) throws Exception {
        List<Integer> ttls = getTTLs();
        for (int rollupLevel = 1; rollupLevel <= configRepository.getRollupConfigs()
                .size(); rollupLevel++) {
            int ttl = ttls.get(rollupLevel);
            rollupLevel(agentRollup, rollupLevel, ttl);
        }
    }

    private void rollupLevel(String agentRollup, int rollupLevel, int ttl) throws Exception {
        List<NeedsRollup> needsRollupList =
                AggregateDao.getNeedsRollupList(agentRollup, rollupLevel, readNeedsRollup, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        Long nextRollupIntervalMillis = null;
        if (rollupLevel < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            for (String gaugeName : needsRollup.getKeys()) {
                int adjustedTTL = getAdjustedTTL(ttl, captureTime);
                rollupOne(rollupLevel, agentRollup, gaugeName, from, captureTime, adjustedTTL);
            }
            AggregateDao.postRollup(agentRollup, rollupLevel, needsRollup, nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, session);
        }
    }

    private SetMultimap<Long, String> getRollupCaptureTimes(List<GaugeValue> gaugeValues) {
        SetMultimap<Long, String> rollupCaptureTimes = HashMultimap.create();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (GaugeValue gaugeValue : gaugeValues) {
            String gaugeName = gaugeValue.getGaugeName();
            long captureTime = gaugeValue.getCaptureTime();
            long intervalMillis = rollupConfigs.get(0).intervalMillis();
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
            rollupCaptureTimes.put(rollupCaptureTime, gaugeName);
        }
        return rollupCaptureTimes;
    }

    // from is non-inclusive
    private void rollupOne(int rollupLevel, String agentRollup, String gaugeName, long from,
            long to, int adjustedTTL) throws Exception {
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
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
        i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(to));
        boundStatement.setDouble(i++, totalWeightedValue / totalWeight);
        boundStatement.setLong(i++, totalWeight);
        boundStatement.setInt(i++, adjustedTTL);
        session.execute(boundStatement);
    }

    private List<Integer> getTTLs() {
        List<Integer> ttls = Lists.newArrayList();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        ttls.add(Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(0))));
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    @OnlyUsedByTests
    void truncateAll() {
        for (int i = 0; i <= configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate gauge_value_rollup_" + i);
        }
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate gauge_needs_rollup_" + i);
        }
        session.execute("truncate gauge_name");
    }

    static int getAdjustedTTL(int ttl, long captureTime) {
        int captureTimeAgoSeconds = Ints
                .saturatedCast(MILLISECONDS.toSeconds(System.currentTimeMillis() - captureTime));
        // max is just a safety guard (primarily used for unit tests)
        return Math.max(ttl - captureTimeAgoSeconds, 60);
    }
}
