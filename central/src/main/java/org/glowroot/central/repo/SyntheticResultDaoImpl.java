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

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.model.Stored;
import org.glowroot.central.util.DummyResultSet;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.model.ErrorIntervalCollector;
import org.glowroot.common.model.ImmutableErrorInterval;
import org.glowroot.common.model.ImmutableSyntheticResult;
import org.glowroot.common.model.SyntheticResult;
import org.glowroot.common.model.SyntheticResult.ErrorInterval;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class SyntheticResultDaoImpl implements SyntheticResultDao {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticResultDaoImpl.class);

    private static final String LCS = "compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertResultPS;
    private final ImmutableList<PreparedStatement> readResultPS;
    private final ImmutableList<PreparedStatement> readResultForRollupPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    SyntheticResultDaoImpl(Session session, ConfigRepositoryImpl configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.clock = clock;

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();

        List<PreparedStatement> insertResultPS = Lists.newArrayList();
        List<PreparedStatement> readResultPS = Lists.newArrayList();
        List<PreparedStatement> readResultForRollupPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            // total_duration_nanos and execution_count only represent successful results
            session.createTableWithTWCS("create table if not exists synthetic_result_rollup_" + i
                    + " (agent_rollup_id varchar, synthetic_config_id varchar,"
                    + " capture_time timestamp, total_duration_nanos double,"
                    + " execution_count bigint, error_intervals blob, primary key"
                    + " ((agent_rollup_id, synthetic_config_id), capture_time))",
                    rollupExpirationHours.get(i));
            insertResultPS.add(session.prepare("insert into synthetic_result_rollup_" + i
                    + " (agent_rollup_id, synthetic_config_id, capture_time, total_duration_nanos,"
                    + " execution_count, error_intervals) values (?, ?, ?, ?, ?, ?) using ttl ?"));
            readResultPS.add(session.prepare("select capture_time, total_duration_nanos,"
                    + " execution_count, error_intervals from synthetic_result_rollup_" + i
                    + " where agent_rollup_id = ? and synthetic_config_id = ? and capture_time >= ?"
                    + " and capture_time <= ?"));
            readResultForRollupPS.add(session.prepare("select total_duration_nanos,"
                    + " execution_count, error_intervals from synthetic_result_rollup_" + i
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

    // synthetic result records are not rolled up to their parent, but are stored directly for
    // rollups that have their own synthetic monitors defined
    @Override
    public void store(String agentRollupId, String syntheticMonitorId, long captureTime,
            long durationNanos, @Nullable String errorMessage) throws Exception {
        int ttl = getTTLs().get(0);
        long maxCaptureTime = 0;
        BoundStatement boundStatement = insertResultPS.get(0).bind();
        maxCaptureTime = Math.max(captureTime, maxCaptureTime);
        int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        boundStatement.setDouble(i++, durationNanos);
        boundStatement.setLong(i++, 1);
        if (errorMessage == null) {
            boundStatement.setToNull(i++);
        } else {
            Stored.ErrorInterval errorInterval = Stored.ErrorInterval.newBuilder()
                    .setFrom(captureTime)
                    .setTo(captureTime)
                    .setCount(1)
                    .setMessage(errorMessage)
                    .setDoNotMergeToTheLeft(false)
                    .setDoNotMergeToTheRight(false)
                    .build();
            boundStatement.setBytes(i++, Messages.toByteBuffer(ImmutableList.of(errorInterval)));
        }
        boundStatement.setInt(i++, adjustedTTL);
        // wait for success before inserting "needs rollup" records
        session.execute(boundStatement);

        // insert into synthetic_needs_rollup_1
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, intervalMillis);
        int needsRollupAdjustedTTL =
                Common.getNeedsRollupAdjustedTTL(adjustedTTL, configRepository.getRollupConfigs());
        boundStatement = insertNeedsRollup.get(0).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setUUID(i++, UUIDs.timeBased());
        boundStatement.setSet(i++, ImmutableSet.of(syntheticMonitorId));
        boundStatement.setInt(i++, needsRollupAdjustedTTL);
        session.execute(boundStatement);
    }

    // from is INCLUSIVE
    @Override
    public List<SyntheticResult> readSyntheticResults(String agentRollupId,
            String syntheticMonitorId, long from, long to, int rollupLevel) throws Exception {
        BoundStatement boundStatement = readResultPS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        ResultSet results = session.execute(boundStatement);
        List<SyntheticResult> syntheticResults = Lists.newArrayList();
        for (Row row : results) {
            i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            ByteBuffer errorIntervalsBytes = row.getBytes(i++);
            List<ErrorInterval> errorIntervals = Lists.newArrayList();
            if (errorIntervalsBytes != null) {
                List<Stored.ErrorInterval> storedErrorIntervals = Messages
                        .parseDelimitedFrom(errorIntervalsBytes, Stored.ErrorInterval.parser());
                for (Stored.ErrorInterval storedErrorInterval : storedErrorIntervals) {
                    errorIntervals.add(ImmutableErrorInterval.builder()
                            .from(storedErrorInterval.getFrom())
                            .to(storedErrorInterval.getTo())
                            .count(storedErrorInterval.getCount())
                            .message(storedErrorInterval.getMessage())
                            .doNotMergeToTheLeft(storedErrorInterval.getDoNotMergeToTheLeft())
                            .doNotMergeToTheRight(storedErrorInterval.getDoNotMergeToTheRight())
                            .build());
                }
            }
            syntheticResults.add(ImmutableSyntheticResult.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .executionCount(executionCount)
                    .addAllErrorIntervals(errorIntervals)
                    .build());
        }
        return syntheticResults;
    }

    @Override
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
        List<NeedsRollup> needsRollupList = Common.getNeedsRollupList(agentRollupId, rollupLevel,
                rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
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
                Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), syntheticMonitorIds,
                        needsRollup.getUniquenessKeysForDeletion(), null, null,
                        deleteNeedsRollup.get(rollupLevel - 1), -1, session);
                continue;
            }
            // wait for above async work to ensure rollup complete before proceeding
            MoreFutures.waitForAll(futures);

            int needsRollupAdjustedTTL =
                    Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
            PreparedStatement insertNeedsRollup = nextRollupIntervalMillis == null ? null
                    : this.insertNeedsRollup.get(rollupLevel);
            PreparedStatement deleteNeedsRollup = this.deleteNeedsRollup.get(rollupLevel - 1);
            Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), syntheticMonitorIds,
                    needsRollup.getUniquenessKeysForDeletion(), nextRollupIntervalMillis,
                    insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session);
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
                },
                // direct executor will run above AsyncFunction inside cassandra driver thread that
                // completes the last future, which is ok since the AsyncFunction itself only kicks
                // off more async work, and should be relatively lightweight itself
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<ResultSet> rollupOneFromRows(int rollupLevel, String agentRollupId,
            String syntheticMonitorId, long to, int adjustedTTL, Iterable<Row> rows)
            throws Exception {
        double totalDurationNanos = 0;
        long executionCount = 0;
        ErrorIntervalCollector errorIntervalCollector = new ErrorIntervalCollector();
        for (Row row : rows) {
            int i = 0;
            totalDurationNanos += row.getDouble(i++);
            executionCount += row.getLong(i++);
            ByteBuffer errorIntervalsBytes = row.getBytes(i++);
            if (errorIntervalsBytes == null) {
                errorIntervalCollector.addGap();
            } else {
                List<Stored.ErrorInterval> errorIntervals = Messages
                        .parseDelimitedFrom(errorIntervalsBytes, Stored.ErrorInterval.parser());
                errorIntervalCollector.addErrorIntervals(fromProto(errorIntervals));
            }
        }
        BoundStatement boundStatement = insertResultPS.get(rollupLevel).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(to));
        boundStatement.setDouble(i++, totalDurationNanos);
        boundStatement.setLong(i++, executionCount);
        List<ErrorInterval> mergedErrorIntervals =
                errorIntervalCollector.getMergedErrorIntervals();
        if (mergedErrorIntervals.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++, Messages.toByteBuffer(toProto(mergedErrorIntervals)));
        }
        boundStatement.setInt(i++, adjustedTTL);
        return session.executeAsync(boundStatement);
    }

    private List<Integer> getTTLs() throws Exception {
        List<Integer> ttls = Lists.newArrayList();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();
        for (long expirationHours : rollupExpirationHours) {
            ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
        }
        return ttls;
    }

    @OnlyUsedByTests
    void truncateAll() throws Exception {
        for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate synthetic_result_rollup_" + i);
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.execute("truncate synthetic_needs_rollup_" + i);
        }
    }

    private static List<ErrorInterval> fromProto(List<Stored.ErrorInterval> storedErrorIntervals) {
        List<ErrorInterval> errorIntervals = Lists.newArrayList();
        for (Stored.ErrorInterval storedErrorInterval : storedErrorIntervals) {
            errorIntervals.add(ImmutableErrorInterval.builder()
                    .from(storedErrorInterval.getFrom())
                    .to(storedErrorInterval.getTo())
                    .count(storedErrorInterval.getCount())
                    .message(storedErrorInterval.getMessage())
                    .doNotMergeToTheLeft(storedErrorInterval.getDoNotMergeToTheLeft())
                    .doNotMergeToTheRight(storedErrorInterval.getDoNotMergeToTheRight())
                    .build());
        }
        return errorIntervals;
    }

    private static List<Stored.ErrorInterval> toProto(List<ErrorInterval> errorIntervals) {
        List<Stored.ErrorInterval> storedErrorIntervals = Lists.newArrayList();
        for (ErrorInterval errorInterval : errorIntervals) {
            storedErrorIntervals.add(Stored.ErrorInterval.newBuilder()
                    .setFrom(errorInterval.from())
                    .setTo(errorInterval.to())
                    .setCount(errorInterval.count())
                    .setMessage(errorInterval.message())
                    .setDoNotMergeToTheLeft(errorInterval.doNotMergeToTheLeft())
                    .setDoNotMergeToTheRight(errorInterval.doNotMergeToTheRight())
                    .build());
        }
        return storedErrorIntervals;
    }
}
