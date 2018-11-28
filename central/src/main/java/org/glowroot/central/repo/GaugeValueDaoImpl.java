/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.Common.NeedsRollupFromChildren;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.MoreFutures.DoRollup;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.util.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

public class GaugeValueDaoImpl implements GaugeValueDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final ExecutorService asyncExecutor;
    private final Clock clock;

    private final GaugeNameDao gaugeNameDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> readValuePS;
    private final ImmutableList<PreparedStatement> readOldestCaptureTimePS;
    private final ImmutableList<PreparedStatement> readValueForRollupPS;
    private final PreparedStatement readValueForRollupFromChildPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement insertNeedsRollupFromChild;
    private final PreparedStatement readNeedsRollupFromChild;
    private final PreparedStatement deleteNeedsRollupFromChild;

    GaugeValueDaoImpl(Session session, ConfigRepositoryImpl configRepository,
            ExecutorService asyncExecutor, Clock clock) throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;
        this.clock = clock;

        gaugeNameDao = new GaugeNameDao(session, configRepository, clock);

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours = Lists
                .newArrayList(configRepository.getCentralStorageConfig().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));

        List<PreparedStatement> insertValuePS = new ArrayList<>();
        List<PreparedStatement> readValuePS = new ArrayList<>();
        List<PreparedStatement> readOldestCaptureTimePS = new ArrayList<>();
        List<PreparedStatement> readValueForRollupPS = new ArrayList<>();
        for (int i = 0; i <= count; i++) {
            // name already has "[counter]" suffix when it is a counter
            session.createTableWithTWCS("create table if not exists gauge_value_rollup_" + i
                    + " (agent_rollup varchar, gauge_name varchar, capture_time timestamp, value"
                    + " double, weight bigint, primary key ((agent_rollup, gauge_name),"
                    + " capture_time))", rollupExpirationHours.get(i));
            insertValuePS.add(session.prepare("insert into gauge_value_rollup_" + i
                    + " (agent_rollup, gauge_name, capture_time, value, weight) values (?, ?, ?, ?,"
                    + " ?) using ttl ?"));
            readValuePS.add(session.prepare("select capture_time, value, weight from"
                    + " gauge_value_rollup_" + i + " where agent_rollup = ? and gauge_name = ? and"
                    + " capture_time >= ? and capture_time <= ?"));
            readOldestCaptureTimePS.add(session.prepare("select capture_time from"
                    + " gauge_value_rollup_" + i + " where agent_rollup = ? and gauge_name = ?"
                    + " limit 1"));
            readValueForRollupPS.add(session.prepare("select value, weight from gauge_value_rollup_"
                    + i + " where agent_rollup = ? and gauge_name = ? and capture_time > ? and"
                    + " capture_time <= ?"));
        }
        this.insertValuePS = ImmutableList.copyOf(insertValuePS);
        this.readValuePS = ImmutableList.copyOf(readValuePS);
        this.readOldestCaptureTimePS = ImmutableList.copyOf(readOldestCaptureTimePS);
        this.readValueForRollupPS = ImmutableList.copyOf(readValueForRollupPS);
        this.readValueForRollupFromChildPS = session.prepare("select value, weight from"
                + " gauge_value_rollup_1 where agent_rollup = ? and gauge_name = ? and"
                + " capture_time = ?");

        // since rollup operations are idempotent, any records resurrected after gc_grace_seconds
        // would just create extra work, but not have any other effect
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long needsRollupGcGraceSeconds = HOURS.toSeconds(4);

        List<PreparedStatement> insertNeedsRollup = new ArrayList<>();
        List<PreparedStatement> readNeedsRollup = new ArrayList<>();
        List<PreparedStatement> deleteNeedsRollup = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            session.createTableWithLCS("create table if not exists gauge_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " gauge_names set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) with gc_grace_seconds = " + needsRollupGcGraceSeconds, true);
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, gauge_names) values (?, ?, ?, ?)"
                    + " using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, gauge_names from"
                    + " gauge_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i + " where"
                    + " agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        session.createTableWithLCS("create table if not exists gauge_needs_rollup_from_child"
                + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                + " child_agent_rollup varchar, gauge_names set<varchar>, primary key"
                + " (agent_rollup, capture_time, uniqueness)) with gc_grace_seconds = "
                + needsRollupGcGraceSeconds, true);
        insertNeedsRollupFromChild = session.prepare("insert into gauge_needs_rollup_from_child"
                + " (agent_rollup, capture_time, uniqueness, child_agent_rollup, gauge_names)"
                + " values (?, ?, ?, ?, ?) using TTL ?");
        readNeedsRollupFromChild = session.prepare("select capture_time, uniqueness,"
                + " child_agent_rollup, gauge_names from gauge_needs_rollup_from_child where"
                + " agent_rollup = ?");
        deleteNeedsRollupFromChild = session.prepare("delete from gauge_needs_rollup_from_child"
                + " where agent_rollup = ? and capture_time = ? and uniqueness = ?");
    }

    @Override
    public void store(String agentId, List<GaugeValue> gaugeValues) throws Exception {
        store(agentId, AgentRollupIds.getAgentRollupIds(agentId), gaugeValues);
    }

    public void store(String agentId, List<String> agentRollupIdsForMeta,
            List<GaugeValue> gaugeValues) throws Exception {
        if (gaugeValues.isEmpty()) {
            return;
        }
        int ttl = getTTLs().get(0);
        long maxCaptureTime = 0;
        List<Future<?>> futures = new ArrayList<>();
        for (GaugeValue gaugeValue : gaugeValues) {
            BoundStatement boundStatement = insertValuePS.get(0).bind();
            String gaugeName = gaugeValue.getGaugeName();
            long captureTime = gaugeValue.getCaptureTime();
            maxCaptureTime = Math.max(captureTime, maxCaptureTime);
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setString(i++, gaugeName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setDouble(i++, gaugeValue.getValue());
            boundStatement.setLong(i++, gaugeValue.getWeight());
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));
            for (String agentRollupIdForMeta : agentRollupIdsForMeta) {
                futures.addAll(gaugeNameDao.insert(agentRollupIdForMeta, captureTime, gaugeName));
            }
        }

        // wait for success before inserting "needs rollup" records
        MoreFutures.waitForAll(futures);
        futures.clear();

        // insert into gauge_needs_rollup_1
        SetMultimap<Long, String> rollupCaptureTimes = getRollupCaptureTimes(gaugeValues);
        for (Map.Entry<Long, Set<String>> entry : Multimaps.asMap(rollupCaptureTimes).entrySet()) {
            BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
            Long captureTime = entry.getKey();
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            int needsRollupAdjustedTTL = Common.getNeedsRollupAdjustedTTL(adjustedTTL,
                    configRepository.getRollupConfigs());
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setSet(i++, entry.getValue());
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
            futures.add(session.writeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
    }

    @Override
    public List<Gauge> getRecentlyActiveGauges(String agentRollupId) throws Exception {
        long now = clock.currentTimeMillis();
        long from = now - DAYS.toMillis(7);
        return getGauges(agentRollupId, from, now + DAYS.toMillis(365));
    }

    @Override
    public List<Gauge> getGauges(String agentRollupId, long from, long to) throws Exception {
        List<Gauge> gauges = new ArrayList<>();
        for (String gaugeName : gaugeNameDao.getGaugeNames(agentRollupId, from, to)) {
            gauges.add(Gauges.getGauge(gaugeName));
        }
        return gauges;
    }

    // from is INCLUSIVE
    @Override
    public List<GaugeValue> readGaugeValues(String agentRollupId, String gaugeName, long from,
            long to, int rollupLevel) throws Exception {
        BoundStatement boundStatement = readValuePS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        ResultSet results = session.read(boundStatement);
        List<GaugeValue> gaugeValues = new ArrayList<>();
        for (Row row : results) {
            i = 0;
            gaugeValues.add(GaugeValue.newBuilder()
                    .setCaptureTime(checkNotNull(row.getTimestamp(i++)).getTime())
                    .setValue(row.getDouble(i++))
                    .setWeight(row.getLong(i++))
                    .build());
        }
        return gaugeValues;
    }

    @Override
    public long getOldestCaptureTime(String agentRollupId, String gaugeName, int rollupLevel)
            throws Exception {
        BoundStatement boundStatement = readOldestCaptureTimePS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        ResultSet results = session.read(boundStatement);
        Row row = results.one();
        return row == null ? Long.MAX_VALUE : checkNotNull(row.getTimestamp(0)).getTime();
    }

    @Override
    public void rollup(String agentRollupId) throws Exception {
        rollup(agentRollupId, AgentRollupIds.getParent(agentRollupId),
                !agentRollupId.endsWith("::"));
    }

    // there is no rollup from children on 5-second gauge values
    //
    // child agent rollups should be processed before their parent agent rollup, since initial
    // parent rollup depends on the 1-minute child rollup
    public void rollup(String agentRollupId, @Nullable String parentAgentRollupId, boolean leaf)
            throws Exception {
        List<Integer> ttls = getTTLs();
        int rollupLevel;
        if (leaf) {
            rollupLevel = 1;
        } else {
            rollupFromChildren(agentRollupId, parentAgentRollupId, ttls.get(1));
            rollupLevel = 2;
        }
        while (rollupLevel <= configRepository.getRollupConfigs().size()) {
            int ttl = ttls.get(rollupLevel);
            rollup(agentRollupId, parentAgentRollupId, rollupLevel, ttl);
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
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
            rollupCaptureTimes.put(rollupCaptureTime, gaugeName);
        }
        return rollupCaptureTimes;
    }

    private void rollupFromChildren(String agentRollupId, @Nullable String parentAgentRollupId,
            int ttl) throws Exception {
        final int rollupLevel = 1;
        List<NeedsRollupFromChildren> needsRollupFromChildrenList = Common
                .getNeedsRollupFromChildrenList(agentRollupId, readNeedsRollupFromChild, session);
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        for (NeedsRollupFromChildren needsRollupFromChildren : needsRollupFromChildrenList) {
            long captureTime = needsRollupFromChildren.getCaptureTime();
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            List<ListenableFuture<?>> futures = new ArrayList<>();
            for (Map.Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys()
                    .asMap()
                    .entrySet()) {
                String gaugeName = entry.getKey();
                Collection<String> childAgentRollupIds = entry.getValue();
                futures.add(rollupOneFromChildren(rollupLevel, agentRollupId, gaugeName,
                        childAgentRollupIds, captureTime, adjustedTTL));
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            if (parentAgentRollupId != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                Common.insertNeedsRollupFromChild(agentRollupId, parentAgentRollupId,
                        insertNeedsRollupFromChild, needsRollupFromChildren, captureTime,
                        needsRollupAdjustedTTL, session);
            }
            Common.postRollup(agentRollupId, needsRollupFromChildren.getCaptureTime(),
                    needsRollupFromChildren.getKeys().keySet(),
                    needsRollupFromChildren.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                    deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session);
        }
    }

    private void rollup(String agentRollupId, @Nullable String parentAgentRollupId, int rollupLevel,
            int ttl) throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        List<NeedsRollup> needsRollupList = Common.getNeedsRollupList(agentRollupId, rollupLevel,
                rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            Set<String> gaugeNames = needsRollup.getKeys();
            List<ListenableFuture<?>> futures = new ArrayList<>();
            for (String gaugeName : gaugeNames) {
                futures.add(rollupOne(rollupLevel, agentRollupId, gaugeName, from, captureTime,
                        adjustedTTL));
            }
            if (futures.isEmpty()) {
                // no rollups occurred, warning already logged inside rollupOne() above
                // this can happen there is an old "needs rollup" record that was created prior to
                // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                // processed (also prior to 0.9.6), and when the corresponding old data has expired
                Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), gaugeNames,
                        needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            if (rollupLevel == 1 && parentAgentRollupId != null) {
                // insert needs to happen first before call to postRollup(), see method-level
                // comment on postRollup
                BoundStatement boundStatement = insertNeedsRollupFromChild.bind();
                int i = 0;
                boundStatement.setString(i++, parentAgentRollupId);
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setUUID(i++, UUIDs.timeBased());
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setSet(i++, gaugeNames);
                boundStatement.setInt(i++, needsRollupAdjustedTTL);
                session.write(boundStatement);
            }
            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), gaugeNames,
                    needsRollup.getUniquenessKeysForDeletion(), nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session);
        }
    }

    private ListenableFuture<?> rollupOneFromChildren(int rollupLevel, String agentRollupId,
            String gaugeName, Collection<String> childAgentRollupIds, long captureTime,
            int adjustedTTL) throws Exception {
        List<ListenableFuture<ResultSet>> futures = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            BoundStatement boundStatement = readValueForRollupFromChildPS.bind();
            int i = 0;
            boundStatement.setString(i++, childAgentRollupId);
            boundStatement.setString(i++, gaugeName);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            futures.add(session.readAsyncWarnIfNoRows(boundStatement, "no gauge value table"
                    + " records found for agentRollupId={}, gaugeName={}, captureTime={}, level={}",
                    childAgentRollupId, gaugeName, captureTime, rollupLevel));
        }
        return MoreFutures.rollupAsync(futures, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOneFromRows(rollupLevel, agentRollupId, gaugeName, captureTime,
                        adjustedTTL, rows);
            }
        });
    }

    // from is non-inclusive
    private ListenableFuture<?> rollupOne(int rollupLevel, String agentRollupId,
            String gaugeName, long from, long to, int adjustedTTL) throws Exception {
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        ListenableFuture<ResultSet> future = session.readAsyncWarnIfNoRows(boundStatement,
                "no gauge value table records found for agentRollupId={}, gaugeName={}, from={},"
                        + " to={}, level={}",
                agentRollupId, gaugeName, from, to, rollupLevel);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOneFromRows(rollupLevel, agentRollupId, gaugeName, to, adjustedTTL,
                        rows);
            }
        });
    }

    private ListenableFuture<?> rollupOneFromRows(int rollupLevel, String agentRollupId,
            String gaugeName, long to, int adjustedTTL, Iterable<Row> rows) throws Exception {
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
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, gaugeName);
        boundStatement.setTimestamp(i++, new Date(to));
        // individual gauge value weights cannot be zero, and rows is non-empty
        // (see callers of this method), so totalWeight is guaranteed non-zero
        checkState(totalWeight != 0);
        boundStatement.setDouble(i++, totalWeightedValue / totalWeight);
        boundStatement.setLong(i++, totalWeight);
        boundStatement.setInt(i++, adjustedTTL);
        return session.writeAsync(boundStatement);
    }

    private List<Integer> getTTLs() throws Exception {
        List<Integer> rollupExpirationHours = Lists
                .newArrayList(configRepository.getCentralStorageConfig().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));
        List<Integer> ttls = new ArrayList<>();
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        for (int i = 0; i <= configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate gauge_value_rollup_" + i);
        }
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate gauge_needs_rollup_" + i);
        }
        session.updateSchemaWithRetry("truncate gauge_name");
        session.updateSchemaWithRetry("truncate gauge_needs_rollup_from_child");
    }
}
