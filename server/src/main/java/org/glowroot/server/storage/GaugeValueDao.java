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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.helper.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

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
                    + " (agent_rollup varchar, capture_time timestamp, gauge_names set<varchar>,"
                    + " last_update timeuuid, primary key (agent_rollup, capture_time)) "
                    + WITH_LCS);
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (agent_rollup, capture_time, gauge_names, last_update) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select agent_rollup, capture_time, gauge_names,"
                    + " last_update from gauge_needs_rollup_" + i));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? if last_update = ?"));
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
        List<Integer> ttls = getTTLs();
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, gaugeValue.getGaugeName());
            boundStatement.setTimestamp(i++, new Date(gaugeValue.getCaptureTime()));
            boundStatement.setDouble(i++, gaugeValue.getValue());
            boundStatement.setLong(i++, gaugeValue.getWeight());
            boundStatement.setInt(i++, ttls.get(0));
            futures.add(session.executeAsync(boundStatement));
            gaugeNameDao.maybeUpdateLastCaptureTime(agentId, gaugeValue.getGaugeName(), futures);
        }
        // insert into gauge_needs_rollup_*
        List<Map<Long, Set<String>>> rollupCaptureTimeGaugeNames =
                getRollupCaptureTimeGaugeNames(gaugeValues);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            Map<Long, Set<String>> map = rollupCaptureTimeGaugeNames.get(i);
            for (Entry<Long, Set<String>> entry : map.entrySet()) {
                long rollupCaptureTime = entry.getKey();
                Set<String> gaugeNames = entry.getValue();
                BoundStatement boundStatement = insertNeedsRollup.get(i).bind();
                boundStatement.setString(0, agentId);
                boundStatement.setTimestamp(1, new Date(rollupCaptureTime));
                boundStatement.setSet(2, gaugeNames);
                boundStatement.setUUID(3, UUIDs.timeBased());
                futures.add(session.executeAsync(boundStatement));
            }
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

    @Override
    public void deleteAll() {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    void rollup() throws Exception {
        List<Integer> ttls = getTTLs();
        for (int rollupLevel = 1; rollupLevel <= configRepository.getRollupConfigs()
                .size(); rollupLevel++) {
            rollupLevel(rollupLevel, ttls);
        }
    }

    private void rollupLevel(int rollupLevel, List<Integer> ttls)
            throws Exception, InterruptedException, ExecutionException {
        ListMultimap<String, Row> agentRows = getNeedsRollup(rollupLevel);
        long rollupIntervalMillis =
                configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        for (String agentRollup : agentRows.keySet()) {
            for (Row row : agentRows.get(agentRollup)) {
                long captureTime = checkNotNull(row.getTimestamp(1)).getTime();
                Set<String> gaugeNames = row.getSet(2, String.class);
                UUID lastUpdate = row.getUUID(3);

                List<ResultSetFuture> futures = Lists.newArrayList();
                for (String gaugeName : gaugeNames) {
                    futures.add(rollupOne(rollupLevel, agentRollup, gaugeName,
                            captureTime - rollupIntervalMillis, captureTime, ttls));
                }
                Futures.allAsList(futures).get();

                BoundStatement boundStatement = deleteNeedsRollup.get(rollupLevel - 1).bind();
                boundStatement.setString(0, agentRollup);
                boundStatement.setTimestamp(1, new Date(captureTime));
                boundStatement.setUUID(2, lastUpdate);
                session.execute(boundStatement);
            }
        }
    }

    private ListMultimap<String, Row> getNeedsRollup(int rollupLevel) {
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        ResultSet results = session.execute(boundStatement);
        ListMultimap<String, Row> agentRows = ArrayListMultimap.create();
        for (Row row : results) {
            String agentRollup = checkNotNull(row.getString(0));
            agentRows.put(agentRollup, row);
        }
        // copy of key set is required since removing a key's last remaining value from a multimap
        // removes the key itself which then triggers ConcurrentModificationException
        for (String agentRollup : ImmutableList.copyOf(agentRows.keySet())) {
            List<Row> rows = agentRows.get(agentRollup);
            // don't roll up the most recent one since it is likely still being added, this is
            // mostly to avoid rolling up this data twice, but also currently the UI assumes when it
            // finds a 1-min rollup it doesn't check for non-rolled up 5-second gauge values
            rows.remove(rows.size() - 1);
        }
        return agentRows;
    }

    private List<Map<Long, Set<String>>> getRollupCaptureTimeGaugeNames(
            List<GaugeValue> gaugeValues) {
        List<Map<Long, Set<String>>> rollupCaptureTimeGaugeNames = Lists.newArrayList();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 1; i <= rollupConfigs.size(); i++) {
            rollupCaptureTimeGaugeNames.add(Maps.newHashMap());
        }
        for (GaugeValue gaugeValue : gaugeValues) {
            String gaugeName = gaugeValue.getGaugeName();
            long captureTime = gaugeValue.getCaptureTime();
            for (int i = 0; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
                Map<Long, Set<String>> map = rollupCaptureTimeGaugeNames.get(i);
                Set<String> gaugeNames = map.get(rollupCaptureTime);
                if (gaugeNames == null) {
                    gaugeNames = Sets.newHashSet();
                    map.put(rollupCaptureTime, gaugeNames);
                }
                gaugeNames.add(gaugeName);
            }
        }
        return rollupCaptureTimeGaugeNames;
    }

    // from is non-inclusive
    private ResultSetFuture rollupOne(int rollupLevel, String agentRollup, String gaugeName,
            long from, long to, List<Integer> ttls) throws Exception {
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind();
        boundStatement.setString(0, agentRollup);
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
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(to));
        boundStatement.setDouble(i++, totalWeightedValue / totalWeight);
        boundStatement.setLong(i++, totalWeight);
        boundStatement.setInt(i++, ttls.get(rollupLevel));
        return session.executeAsync(boundStatement);
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
}
