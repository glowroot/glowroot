/*
 * Copyright 2017 the original author or authors.
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

import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AggregateDao.NeedsRollup;
import org.glowroot.central.util.DummyResultSet;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.ImmutableSyntheticResult;
import org.glowroot.common.repo.SyntheticResultRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class SyntheticResultDao implements SyntheticResultRepository {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticResultDao.class);

    private static final String LCS = "compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepository configRepository;
    private final Clock clock;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertResultPS;
    private final ImmutableList<PreparedStatement> readResultPS;
    private final ImmutableList<PreparedStatement> readResultForRollupPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    public SyntheticResultDao(Session session, ConfigRepository configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.clock = clock;

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();

        List<PreparedStatement> insertResultPS = Lists.newArrayList();
        List<PreparedStatement> readResultPS = Lists.newArrayList();
        List<PreparedStatement> readResultForRollupPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            Sessions.createTableWithTWCS(session, "create table if not exists"
                    + " synthetic_result_rollup_" + i + " (agent_rollup_id varchar,"
                    + " synthetic_config_id varchar, capture_time timestamp,"
                    + " total_duration_nanos double, execution_count bigint, error_count bigint,"
                    + " primary key ((agent_rollup_id, synthetic_config_id), capture_time))",
                    rollupExpirationHours.get(i));
            insertResultPS.add(session.prepare("insert into synthetic_result_rollup_" + i
                    + " (agent_rollup_id, synthetic_config_id, capture_time, total_duration_nanos,"
                    + " execution_count, error_count) values (?, ?, ?, ?, ?, ?) using ttl ?"));
            readResultPS.add(session.prepare("select capture_time, total_duration_nanos,"
                    + " execution_count, error_count from synthetic_result_rollup_" + i
                    + " where agent_rollup_id = ? and synthetic_config_id = ? and capture_time >= ?"
                    + " and capture_time <= ?"));
            readResultForRollupPS.add(session.prepare("select total_duration_nanos,"
                    + " execution_count, error_count from synthetic_result_rollup_" + i
                    + " where agent_rollup_id = ? and synthetic_config_id = ? and capture_time > ?"
                    + " and capture_time <= ?"));
        }
        this.insertResultPS = ImmutableList.copyOf(insertResultPS);
        this.readResultPS = ImmutableList.copyOf(readResultPS);
        this.readResultForRollupPS = ImmutableList.copyOf(readResultForRollupPS);

        // since rollup operations are idempotent, any records resurrected after gc_grace_seconds
        // would just create extra work, but not have any other effect
        //
        // 3 hours is chosen to match default max_hint_window_in_ms since hints are stored
        // with a TTL of gc_grace_seconds
        // (see http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        long needsRollupGcGraceSeconds = HOURS.toSeconds(3);

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> deleteNeedsRollup = Lists.newArrayList();
        for (int i = 1; i < count; i++) {
            session.execute("create table if not exists synthetic_needs_rollup_" + i
                    + " (agent_rollup_id varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " synthetic_config_ids set<varchar>, primary key (agent_rollup_id,"
                    + " capture_time, uniqueness)) with gc_grace_seconds = "
                    + needsRollupGcGraceSeconds + " and " + LCS);
            insertNeedsRollup.add(session.prepare("insert into synthetic_needs_rollup_" + i
                    + " (agent_rollup_id, capture_time, uniqueness, synthetic_config_ids) values"
                    + " (?, ?, ?, ?) using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness,"
                    + " synthetic_config_ids from synthetic_needs_rollup_" + i
                    + " where agent_rollup_id = ?"));
            deleteNeedsRollup.add(session.prepare("delete from synthetic_needs_rollup_" + i
                    + " where agent_rollup_id = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;
    }

    public void store(String agentId, String syntheticMonitorId, long captureTime,
            long durationNanos, boolean error) throws Exception {
        int ttl = getTTLs().get(0);
        long maxCaptureTime = 0;
        BoundStatement boundStatement = insertResultPS.get(0).bind();
        maxCaptureTime = Math.max(captureTime, maxCaptureTime);
        int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, durationNanos);
        boundStatement.setLong(i++, 1);
        boundStatement.setLong(i++, error ? 1 : 0);
        boundStatement.setInt(i++, adjustedTTL);
        // wait for success before inserting "needs rollup" records
        session.execute(boundStatement);

        // insert into synthetic_needs_rollup_1
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
        int needsRollupAdjustedTTL = AggregateDao.getNeedsRollupAdjustedTTL(adjustedTTL,
                configRepository.getRollupConfigs());
        boundStatement = insertNeedsRollup.get(0).bind();
        i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setUUID(i++, UUIDs.timeBased());
        boundStatement.setSet(i++, ImmutableSet.of(syntheticMonitorId));
        boundStatement.setInt(i++, needsRollupAdjustedTTL);
        session.execute(boundStatement);
    }

    // captureTimeFrom is INCLUSIVE
    @Override
    public List<SyntheticResult> readSyntheticResults(String agentRollupId,
            String syntheticMonitorId, long captureTimeFrom, long captureTimeTo, int rollupLevel) {
        BoundStatement boundStatement = readResultPS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(captureTimeFrom));
        boundStatement.setTimestamp(i++, new Date(captureTimeTo));
        ResultSet results = session.execute(boundStatement);
        List<SyntheticResult> syntheticResults = Lists.newArrayList();
        for (Row row : results) {
            i = 0;
            syntheticResults.add(ImmutableSyntheticResult.builder()
                    .captureTime(checkNotNull(row.getTimestamp(i++)).getTime())
                    .totalDurationNanos(row.getDouble(i++))
                    .executionCount(row.getLong(i++))
                    .errorCount(row.getLong(i++))
                    .build());
        }
        return syntheticResults;
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Rollup synthetic results",
            traceHeadline = "Rollup synthetic results: {{0}}", timer = "rollup synthetic results")
    public void rollup(String agentRollupId) throws Exception {
        List<Integer> ttls = getTTLs();
        int rollupLevel = 1;
        while (rollupLevel < configRepository.getRollupConfigs().size()) {
            int ttl = ttls.get(rollupLevel);
            rollup(agentRollupId, rollupLevel, ttl);
            rollupLevel++;
        }
    }

    private void rollup(String agentRollupId, int rollupLevel, int ttl) throws Exception {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        List<NeedsRollup> needsRollupList = AggregateDao.getNeedsRollupList(agentRollupId,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            int adjustedTTL = AggregateDao.getAdjustedTTL(ttl, captureTime, clock);
            Set<String> syntheticMonitorIds = needsRollup.getKeys();
            List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
            for (String syntheticMonitorId : syntheticMonitorIds) {
                futures.add(rollupOne(rollupLevel, agentRollupId, syntheticMonitorId, from,
                        captureTime, adjustedTTL));
            }
            if (futures.isEmpty()) {
                // no rollups occurred, warning already logged inside rollupOne() above
                // this can happen there is an old "needs rollup" record that was created prior to
                // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                // processed (also prior to 0.9.6), and when the corresponding old data has expired
                AggregateDao.postRollup(agentRollupId, needsRollup.getCaptureTime(),
                        syntheticMonitorIds, needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            int needsRollupAdjustedTTL =
                    AggregateDao.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            AggregateDao.postRollup(agentRollupId, needsRollup.getCaptureTime(),
                    syntheticMonitorIds, needsRollup.getUniquenessKeysForDeletion(),
                    nextRollupIntervalMillis, insertNeedsRollup, deleteNeedsRollup,
                    needsRollupAdjustedTTL, session);
        }
    }

    // from is non-inclusive
    private ListenableFuture<ResultSet> rollupOne(int rollupLevel, String agentRollupId,
            String syntheticMonitorId, long from, long to, int adjustedTTL) throws Exception {
        BoundStatement boundStatement = readResultForRollupPS.get(rollupLevel - 1).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        return Futures.transformAsync(
                session.executeAsync(boundStatement),
                new AsyncFunction<ResultSet, ResultSet>() {
                    @Override
                    public ListenableFuture<ResultSet> apply(@Nullable ResultSet results)
                            throws Exception {
                        checkNotNull(results);
                        if (results.isExhausted()) {
                            // this is unexpected since TTL for "needs rollup" records is shorter
                            // than TTL for data
                            logger.warn("no synthetic result table records found for"
                                    + " agentRollupId={}, syntheticMonitorId={}, from={}, to={},"
                                    + " level={}", agentRollupId, syntheticMonitorId, from, to,
                                    rollupLevel);
                            return Futures.immediateFuture(DummyResultSet.INSTANCE);
                        }
                        return rollupOneFromRows(rollupLevel, agentRollupId, syntheticMonitorId, to,
                                adjustedTTL, results);
                    }
                });
    }

    private ListenableFuture<ResultSet> rollupOneFromRows(int rollupLevel, String agentRollupId,
            String syntheticMonitorId, long to, int adjustedTTL, Iterable<Row> rows) {
        double totalDurationNanos = 0;
        long executionCount = 0;
        long errorCount = 0;
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            executionCount += row.getLong(i++);
            errorCount += row.getLong(i++);
        }
        BoundStatement boundStatement = insertResultPS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(to));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, executionCount);
        boundStatement.setLong(i++, errorCount);
        boundStatement.setInt(i++, adjustedTTL);
        return session.executeAsync(boundStatement);
    }

    private List<Integer> getTTLs() throws Exception {
        List<Integer> ttls = Lists.newArrayList();
        List<Integer> rollupExpirationHours =
                configRepository.getStorageConfig().rollupExpirationHours();
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    @OnlyUsedByTests
    void truncateAll() {
        for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate synthetic_result_rollup_" + i);
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate synthetic_needs_rollup_" + i);
        }
    }
}
