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

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrument;
import org.glowroot.central.storage.AggregateDao.NeedsRollup;
import org.glowroot.central.storage.AggregateDao.NeedsRollupFromChildren;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.Gauges;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class GaugeValueDao implements GaugeValueRepository {

    private static Logger logger = LoggerFactory.getLogger(GaugeValueDao.class);

    private static final String DTCS = "compaction = { 'class' : 'DateTieredCompactionStrategy' }";

    private static final String LCS = "compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final AgentDao agentDao;
    private final ConfigRepository configRepository;
    private final Clock clock;

    private final GaugeNameDao gaugeNameDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> readValuePS;
    private final ImmutableList<PreparedStatement> readValueForRollupPS;
    private final PreparedStatement readValueForRollupFromChildPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement insertNeedsRollupFromChild;
    private final PreparedStatement readNeedsRollupFromChild;
    private final PreparedStatement deleteNeedsRollupFromChild;

    public GaugeValueDao(Session session, AgentDao agentDao, ConfigRepository configRepository,
            Clock clock) {
        this.session = session;
        this.agentDao = agentDao;
        this.configRepository = configRepository;
        this.clock = clock;

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
                    + " capture_time)) with " + DTCS);
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
        this.readValueForRollupFromChildPS = session.prepare("select value, weight from"
                + " gauge_value_rollup_1 where agent_rollup = ? and gauge_name = ?"
                + " and capture_time = ?");

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i <= count; i++) {
            session.execute("create table if not exists gauge_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " gauge_names set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) with gc_grace_seconds = 7200 and " + LCS);
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, gauge_names) values"
                    + " (?, ?, ?, ?) using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, gauge_names"
                    + " from gauge_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i
                    + " where agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        session.execute("create table if not exists gauge_needs_rollup_from_child"
                + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                + " child_agent_rollup varchar, gauge_names set<varchar>,"
                + " primary key (agent_rollup, capture_time, uniqueness))"
                + " with gc_grace_seconds = 7200 and " + LCS);
        insertNeedsRollupFromChild = session.prepare("insert into gauge_needs_rollup_from_child"
                + " (agent_rollup, capture_time, uniqueness, child_agent_rollup, gauge_names)"
                + " values (?, ?, ?, ?, ?) using TTL ?");
        readNeedsRollupFromChild = session.prepare("select capture_time, uniqueness,"
                + " child_agent_rollup, gauge_names from gauge_needs_rollup_from_child"
                + " where agent_rollup = ?");
        deleteNeedsRollupFromChild = session.prepare("delete from gauge_needs_rollup_from_child"
                + " where agent_rollup = ? and capture_time = ? and uniqueness = ?");
    }

    public void store(String agentId, List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        List<String> agentRollups = agentDao.readAgentRollups(agentId);
        int ttl = getTTLs().get(0);
        List<ResultSetFuture> futures = Lists.newArrayList();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            String gaugeName = gaugeValue.getGaugeName();
            long captureTime = gaugeValue.getCaptureTime();
            int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, gaugeName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setDouble(i++, gaugeValue.getValue());
            boundStatement.setLong(i++, gaugeValue.getWeight());
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
            for (String agentRollup : agentRollups) {
                futures.addAll(gaugeNameDao.store(agentRollup, gaugeName));
            }
        }
        // insert into gauge_needs_rollup_1
        SetMultimap<Long, String> rollupCaptureTimes = getRollupCaptureTimes(gaugeValues);
        for (Entry<Long, Set<String>> entry : Multimaps.asMap(rollupCaptureTimes).entrySet()) {
            BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
            Long captureTime = entry.getKey();
            int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
            int needsRollupAdjustedTTL = AggregateDao.getNeedsRollupAdjustedTTL(adjustedTTL,
                    configRepository.getRollupConfigs());
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setSet(i++, entry.getValue());
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
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
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(captureTimeFrom));
        boundStatement.setTimestamp(i++, new Date(captureTimeTo));
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

    // there is no rollup from children on 5-second gauge values
    //
    // child agent rollups should be processed before their parent agent rollup, since initial
    // parent rollup depends on the 1-minute child rollup
    @Instrument.Transaction(transactionType = "Rollup", transactionName = "Gauge rollup",
            traceHeadline = "Gauge rollup: {{0}}", timerName = "gauge rollup")
    public void rollup(String agentRollup, @Nullable String parentAgentRollup, boolean leaf)
            throws Exception {
        List<Integer> ttls = getTTLs();
        int rollupLevel;
        if (leaf) {
            rollupLevel = 1;
        } else {
            rollupFromChildren(agentRollup, parentAgentRollup, ttls.get(1));
            rollupLevel = 2;
        }
        while (rollupLevel <= configRepository.getRollupConfigs().size()) {
            int ttl = ttls.get(rollupLevel);
            rollup(agentRollup, parentAgentRollup, rollupLevel, ttl);
            rollupLevel++;
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

    private void rollupFromChildren(String agentRollup, @Nullable String parentAgentRollup, int ttl)
            throws Exception {
        final int rollupLevel = 1;
        List<NeedsRollupFromChildren> needsRollupFromChildrenList = AggregateDao
                .getNeedsRollupFromChildrenList(agentRollup, readNeedsRollupFromChild, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();

        for (NeedsRollupFromChildren needsRollupFromChildren : needsRollupFromChildrenList) {
            long captureTime = needsRollupFromChildren.getCaptureTime();
            int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
            List<ResultSetFuture> futures = Lists.newArrayList();
            for (Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys().asMap()
                    .entrySet()) {
                String gaugeName = entry.getKey();
                Collection<String> childAgentRollups = entry.getValue();
                futures.addAll(rollupOneFromChildren(rollupLevel, agentRollup, gaugeName,
                        childAgentRollups, captureTime, adjustedTTL));
            }
            // wait for above async work to ensure rollup complete before proceeding
            Futures.allAsList(futures).get();

            int needsRollupAdjustedTTL =
                    AggregateDao.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            if (parentAgentRollup != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
                int i = 0;
                boundStatement.setString(i++, parentAgentRollup);
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setUUID(i++, UUIDs.timeBased());
                boundStatement.setString(i++, agentRollup);
                boundStatement.setSet(i++, needsRollupFromChildren.getKeys().keySet());
                boundStatement.setInt(i++, needsRollupAdjustedTTL);
                session.execute(boundStatement);
            }
            AggregateDao.postRollup(agentRollup, needsRollupFromChildren.getCaptureTime(),
                    needsRollupFromChildren.getKeys().keySet(),
                    needsRollupFromChildren.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                    deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session);
        }
    }

    private void rollup(String agentRollup, @Nullable String parentAgentRollup, int rollupLevel,
            int ttl) throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        List<NeedsRollup> needsRollupList = AggregateDao.getNeedsRollupList(agentRollup,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
            Set<String> gaugeNames = needsRollup.getKeys();
            List<ResultSetFuture> futures = Lists.newArrayList();
            for (String gaugeName : gaugeNames) {
                futures.addAll(rollupOne(rollupLevel, agentRollup, gaugeName, from, captureTime,
                        adjustedTTL));
            }
            if (futures.isEmpty()) {
                // no rollups occurred, warning already logged inside rollupOne() above
                // this can happen there is an old "needs rollup" record that was created prior to
                // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                // processed (also prior to 0.9.6), and when the corresponding old data has expired
                AggregateDao.postRollup(agentRollup, needsRollup.getCaptureTime(),
                        gaugeNames, needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            Futures.allAsList(futures).get();

            int needsRollupAdjustedTTL =
                    AggregateDao.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            if (rollupLevel == 1 && parentAgentRollup != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
                int i = 0;
                boundStatement.setString(i++, parentAgentRollup);
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setUUID(i++, UUIDs.timeBased());
                boundStatement.setString(i++, agentRollup);
                boundStatement.setSet(i++, gaugeNames);
                boundStatement.setInt(i++, needsRollupAdjustedTTL);
                session.execute(boundStatement);
            }
            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            AggregateDao.postRollup(agentRollup, needsRollup.getCaptureTime(),
                    gaugeNames, needsRollup.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup, deleteNeedsRollup,
                    needsRollupAdjustedTTL, session);
        }
    }

    private List<ResultSetFuture> rollupOneFromChildren(int rollupLevel, String agentRollup,
            String gaugeName, Collection<String> childAgentRollups, long captureTime,
            int adjustedTTL) {
        List<Row> rows = Lists.newArrayList();
        for (String childAgentRollup : childAgentRollups) {
            BoundStatement boundStatement = readValueForRollupFromChildPS.bind();
            int i = 0;
            boundStatement.setString(i++, childAgentRollup);
            boundStatement.setString(i++, gaugeName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            Row row = session.execute(boundStatement).one();
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no gauge value table records found for agentRollup={}, gaugeName={},"
                    + " captureTime={}, level={}", agentRollup, gaugeName, captureTime,
                    rollupLevel);
            return ImmutableList.of();
        }
        return rollupOneFromRows(rollupLevel, agentRollup, gaugeName, captureTime, adjustedTTL,
                rows);
    }

    // from is non-inclusive
    private List<ResultSetFuture> rollupOne(int rollupLevel, String agentRollup, String gaugeName,
            long from, long to, int adjustedTTL) throws Exception {
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            // this is unexpected since TTL for "needs rollup" records is shorter than TTL for data
            logger.warn("no gauge value table records found for agentRollup={}, gaugeName={},"
                    + " from={}, to={}, level={}", agentRollup, gaugeName, from, to, rollupLevel);
            return ImmutableList.of();
        }
        return rollupOneFromRows(rollupLevel, agentRollup, gaugeName, to, adjustedTTL, results);
    }

    private List<ResultSetFuture> rollupOneFromRows(int rollupLevel, String agentRollup,
            String gaugeName, long to, int adjustedTTL, Iterable<Row> rows) {
        double totalWeightedValue = 0;
        long totalWeight = 0;
        for (Row row : rows) {
            double value = row.getDouble(0);
            long weight = row.getLong(1);
            totalWeightedValue += value * weight;
            totalWeight += weight;
        }
        BoundStatement boundStatement = insertValuePS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollup);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(to));
        boundStatement.setDouble(i++, totalWeightedValue / totalWeight);
        boundStatement.setLong(i++, totalWeight);
        boundStatement.setInt(i++, adjustedTTL);
        return ImmutableList.of(session.executeAsync(boundStatement));
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
        session.execute("truncate gauge_needs_rollup_from_child");
    }
}
