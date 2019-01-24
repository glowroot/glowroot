/*
 * Copyright 2017-2019 the original author or authors.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.proto.Stored;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.MoreFutures.DoRollup;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.ErrorIntervalCollector;
import org.glowroot.common2.repo.ImmutableErrorInterval;
import org.glowroot.common2.repo.ImmutableSyntheticResult;
import org.glowroot.common2.repo.SyntheticResult;
import org.glowroot.common2.repo.SyntheticResult.ErrorInterval;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class SyntheticResultDaoImpl implements SyntheticResultDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Executor asyncExecutor;
    private final Clock clock;

    private final SyntheticMonitorIdDao syntheticMonitorIdDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertResultPS;
    private final ImmutableList<PreparedStatement> readResultPS;
    private final ImmutableList<PreparedStatement> readResultForRollupPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement readLastFromRollup0;

    SyntheticResultDaoImpl(Session session, ConfigRepositoryImpl configRepository,
            Executor asyncExecutor, Clock clock) throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;
        this.clock = clock;

        syntheticMonitorIdDao = new SyntheticMonitorIdDao(session, configRepository, clock);

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();

        List<PreparedStatement> insertResultPS = new ArrayList<>();
        List<PreparedStatement> readResultPS = new ArrayList<>();
        List<PreparedStatement> readResultForRollupPS = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // total_duration_nanos and execution_count only represent successful results
            session.createTableWithTWCS("create table if not exists synthetic_result_rollup_" + i
                    + " (agent_rollup_id varchar, synthetic_config_id varchar, capture_time"
                    + " timestamp, total_duration_nanos double, execution_count bigint,"
                    + " error_intervals blob, primary key ((agent_rollup_id, synthetic_config_id),"
                    + " capture_time))", rollupExpirationHours.get(i));
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
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long needsRollupGcGraceSeconds = HOURS.toSeconds(4);

        List<PreparedStatement> insertNeedsRollup = new ArrayList<>();
        List<PreparedStatement> readNeedsRollup = new ArrayList<>();
        List<PreparedStatement> deleteNeedsRollup = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            session.createTableWithLCS("create table if not exists synthetic_needs_rollup_" + i
                    + " (agent_rollup_id varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " synthetic_config_ids set<varchar>, primary key (agent_rollup_id,"
                    + " capture_time, uniqueness)) with gc_grace_seconds = "
                    + needsRollupGcGraceSeconds, true);
            insertNeedsRollup.add(session.prepare("insert into synthetic_needs_rollup_" + i
                    + " (agent_rollup_id, capture_time, uniqueness, synthetic_config_ids) values"
                    + " (?, ?, ?, ?) using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness,"
                    + " synthetic_config_ids from synthetic_needs_rollup_" + i + " where"
                    + " agent_rollup_id = ?"));
            deleteNeedsRollup.add(session.prepare("delete from synthetic_needs_rollup_" + i
                    + " where agent_rollup_id = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        readLastFromRollup0 = session.prepare("select capture_time, total_duration_nanos,"
                + " error_intervals from synthetic_result_rollup_0 where agent_rollup_id = ? and"
                + " synthetic_config_id = ? order by capture_time desc limit ?");
    }

    // synthetic result records are not rolled up to their parent, but are stored directly for
    // rollups that have their own synthetic monitors defined
    @Override
    public void store(String agentRollupId, String syntheticMonitorId,
            String syntheticMonitorDisplay, long captureTime, long durationNanos,
            @Nullable String errorMessage) throws Exception {
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
        List<Future<?>> futures = new ArrayList<>();
        futures.add(session.writeAsync(boundStatement));
        futures.addAll(syntheticMonitorIdDao.insert(agentRollupId, captureTime, syntheticMonitorId,
                syntheticMonitorDisplay));

        // wait for success before inserting "needs rollup" records
        MoreFutures.waitForAll(futures);

        // insert into synthetic_needs_rollup_1
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
        int needsRollupAdjustedTTL =
                Common.getNeedsRollupAdjustedTTL(adjustedTTL, configRepository.getRollupConfigs());
        boundStatement = insertNeedsRollup.get(0).bind();
        i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setUUID(i++, UUIDs.timeBased());
        boundStatement.setSet(i++, ImmutableSet.of(syntheticMonitorId));
        boundStatement.setInt(i++, needsRollupAdjustedTTL);
        session.write(boundStatement);
    }

    @Override
    public Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to)
            throws Exception {
        return syntheticMonitorIdDao.getSyntheticMonitorIds(agentRollupId, from, to);
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
        ResultSet results = session.read(boundStatement);
        List<SyntheticResult> syntheticResults = new ArrayList<>();
        for (Row row : results) {
            i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long executionCount = row.getLong(i++);
            ByteBuffer errorIntervalsBytes = row.getBytes(i++);
            List<ErrorInterval> errorIntervals = new ArrayList<>();
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
    public List<SyntheticResultRollup0> readLastFromRollup0(String agentRollupId,
            String syntheticMonitorId, int x) throws Exception {
        BoundStatement boundStatement = readLastFromRollup0.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setInt(i++, x);
        ResultSet results = session.read(boundStatement);
        List<SyntheticResultRollup0> syntheticResults = new ArrayList<>();
        for (Row row : results) {
            i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            ByteBuffer errorIntervalsBytes = row.getBytes(i++);
            syntheticResults.add(ImmutableSyntheticResultRollup0.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .error(errorIntervalsBytes != null)
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
        Collection<NeedsRollup> needsRollupList = Common.getNeedsRollupList(agentRollupId,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock);
        Long nextRollupIntervalMillis = null;
        if (rollupLevel + 1 < rollupConfigs.size()) {
            nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        }
        for (NeedsRollup needsRollup : needsRollupList) {
            long captureTime = needsRollup.getCaptureTime();
            long from = captureTime - rollupIntervalMillis;
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            Set<String> syntheticMonitorIds = needsRollup.getKeys();
            List<ListenableFuture<?>> futures = new ArrayList<>();
            for (String syntheticMonitorId : syntheticMonitorIds) {
                futures.add(rollupOne(rollupLevel, agentRollupId, syntheticMonitorId, from,
                        captureTime, adjustedTTL));
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
    private ListenableFuture<?> rollupOne(int rollupLevel, String agentRollupId,
            String syntheticMonitorId, long from, long to, int adjustedTTL) throws Exception {
        BoundStatement boundStatement = readResultForRollupPS.get(rollupLevel - 1).bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setString(i++, syntheticMonitorId);
        boundStatement.setTimestamp(i++, new Date(from));
        boundStatement.setTimestamp(i++, new Date(to));
        ListenableFuture<ResultSet> future = session.readAsyncWarnIfNoRows(boundStatement,
                "no synthetic result table records found for agentRollupId={},"
                        + " syntheticMonitorId={}, from={}, to={}, level={}",
                agentRollupId, syntheticMonitorId, from, to, rollupLevel);
        return MoreFutures.rollupAsync(future, asyncExecutor, new DoRollup() {
            @Override
            public ListenableFuture<?> execute(Iterable<Row> rows) throws Exception {
                return rollupOneFromRows(rollupLevel, agentRollupId, syntheticMonitorId, to,
                        adjustedTTL, rows);
            }
        });
    }

    private ListenableFuture<?> rollupOneFromRows(int rollupLevel, String agentRollupId,
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
        List<ErrorInterval> mergedErrorIntervals = errorIntervalCollector.getMergedErrorIntervals();
        if (mergedErrorIntervals.isEmpty()) {
            boundStatement.setToNull(i++);
        } else {
            boundStatement.setBytes(i++, Messages.toByteBuffer(toProto(mergedErrorIntervals)));
        }
        boundStatement.setInt(i++, adjustedTTL);
        return session.writeAsync(boundStatement);
    }

    private List<Integer> getTTLs() throws Exception {
        List<Integer> ttls = new ArrayList<>();
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
            session.updateSchemaWithRetry("truncate synthetic_result_rollup_" + i);
        }
        for (int i = 1; i < configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate synthetic_needs_rollup_" + i);
        }
    }

    private static List<ErrorInterval> fromProto(List<Stored.ErrorInterval> storedErrorIntervals) {
        List<ErrorInterval> errorIntervals = new ArrayList<>();
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
        List<Stored.ErrorInterval> storedErrorIntervals = new ArrayList<>();
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
