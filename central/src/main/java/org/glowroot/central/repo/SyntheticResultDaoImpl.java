/*
 * Copyright 2017-2023 the original author or authors.
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

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.spotify.futures.CompletableFutures;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.proto.Stored;
import org.glowroot.central.util.Messages;
import org.glowroot.central.util.MoreFutures;
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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.glowroot.common2.repo.CassandraProfile.*;

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
                           Executor asyncExecutor, int cassandraGcGraceSeconds, Clock clock) throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;
        this.clock = clock;

        syntheticMonitorIdDao = new SyntheticMonitorIdDao(session, configRepository, clock);

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().toCompletableFuture().join().rollupExpirationHours();

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

        List<PreparedStatement> insertNeedsRollup = new ArrayList<>();
        List<PreparedStatement> readNeedsRollup = new ArrayList<>();
        List<PreparedStatement> deleteNeedsRollup = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            session.createTableWithLCS("create table if not exists synthetic_needs_rollup_" + i
                    + " (agent_rollup_id varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " synthetic_config_ids set<varchar>, primary key (agent_rollup_id,"
                    + " capture_time, uniqueness)) with gc_grace_seconds = "
                    + cassandraGcGraceSeconds, true);
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
    public CompletionStage<?> store(String agentRollupId, String syntheticMonitorId,
                                    String syntheticMonitorDisplay, long captureTime, long durationNanos,
                                    @Nullable String errorMessage) {
        return getTTLs().thenCompose(ttls -> {
            int ttl = ttls.get(0);
            long maxCaptureTime = 0;
            BoundStatement boundStmt = insertResultPS.get(0).bind();
            maxCaptureTime = Math.max(captureTime, maxCaptureTime);
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
            int j = 0;
            boundStmt = boundStmt.setString(j++, agentRollupId)
                    .setString(j++, syntheticMonitorId)
                    .setInstant(j++, Instant.ofEpochMilli(captureTime))
                    .setDouble(j++, durationNanos)
                    .setLong(j++, 1);
            if (errorMessage == null) {
                boundStmt = boundStmt.setToNull(j++);
            } else {
                Stored.ErrorInterval errorInterval = Stored.ErrorInterval.newBuilder()
                        .setFrom(captureTime)
                        .setTo(captureTime)
                        .setCount(1)
                        .setMessage(errorMessage)
                        .setDoNotMergeToTheLeft(false)
                        .setDoNotMergeToTheRight(false)
                        .build();
                boundStmt = boundStmt.setByteBuffer(j++, Messages.toByteBuffer(ImmutableList.of(errorInterval)));
            }
            boundStmt = boundStmt.setInt(j++, adjustedTTL);
            List<CompletionStage<?>> futures = new ArrayList<>();
            futures.add(session.writeAsync(boundStmt, collector).toCompletableFuture());
            futures.add(syntheticMonitorIdDao.insert(agentRollupId, captureTime, syntheticMonitorId,
                    syntheticMonitorDisplay));

            // wait for success before inserting "needs rollup" records
            return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
                // insert into synthetic_needs_rollup_1
                List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
                long intervalMillis = rollupConfigs.get(1).intervalMillis();
                long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
                int needsRollupAdjustedTTL =
                        Common.getNeedsRollupAdjustedTTL(adjustedTTL, configRepository.getRollupConfigs());
                int i = 0;
                BoundStatement boundStatement = insertNeedsRollup.get(0).bind()
                        .setString(i++, agentRollupId)
                        .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                        .setUuid(i++, Uuids.timeBased())
                        .setSet(i++, ImmutableSet.of(syntheticMonitorId), String.class)
                        .setInt(i++, needsRollupAdjustedTTL);
                return session.writeAsync(boundStatement, collector);
            });
        });
    }

    @Override
    public Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to)
            throws Exception {
        return syntheticMonitorIdDao.getSyntheticMonitorIds(agentRollupId, from, to);
    }

    // from is INCLUSIVE
    @Override
    public CompletionStage<List<SyntheticResult>> readSyntheticResults(String agentRollupId,
                                                                       String syntheticMonitorId, long from, long to, int rollupLevel) {
        int i = 0;
        BoundStatement boundStatement = readResultPS.get(rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, syntheticMonitorId)
                .setInstant(i++, Instant.ofEpochMilli(from))
                .setInstant(i++, Instant.ofEpochMilli(to));
        List<SyntheticResult> syntheticResults = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<SyntheticResult>>> compute = new Function<AsyncResultSet, CompletableFuture<List<SyntheticResult>>>() {
            @Override
            public CompletableFuture<List<SyntheticResult>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    double totalDurationNanos = row.getDouble(i++);
                    long executionCount = row.getLong(i++);
                    ByteBuffer errorIntervalsBytes = row.getByteBuffer(i++);
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
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(syntheticResults);
            }
        };
        return session.readAsync(boundStatement, web).thenCompose(compute);
    }

    @Override
    public CompletionStage<List<SyntheticResultRollup0>> readLastFromRollup0(String agentRollupId,
                                                                             String syntheticMonitorId, int x) {
        int i = 0;
        BoundStatement boundStatement = readLastFromRollup0.bind()
                .setString(i++, agentRollupId)
                .setString(i++, syntheticMonitorId)
                .setInt(i++, x);

        List<SyntheticResultRollup0> syntheticResults = new ArrayList<>();

        Function<AsyncResultSet, CompletableFuture<List<SyntheticResultRollup0>>> compute = new Function<AsyncResultSet, CompletableFuture<List<SyntheticResultRollup0>>>() {
            @Override
            public CompletableFuture<List<SyntheticResultRollup0>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    double totalDurationNanos = row.getDouble(i++);
                    ByteBuffer errorIntervalsBytes = row.getByteBuffer(i++);
                    syntheticResults.add(ImmutableSyntheticResultRollup0.builder()
                            .captureTime(captureTime)
                            .totalDurationNanos(totalDurationNanos)
                            .error(errorIntervalsBytes != null)
                            .build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(syntheticResults);
            }
        };
        return session.readAsync(boundStatement, rollup).thenCompose(compute);
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return getTTLs().thenCompose(ttls -> {
            int rollupLevel = 1;
            CompletionStage<?> starting = CompletableFuture.completedFuture(null);

            Function<Integer, CompletionStage<Integer>> lambda = new Function<>() {
                @Override
                public CompletionStage<Integer> apply(Integer rollupLevelInner) {
                    if (rollupLevelInner < configRepository.getRollupConfigs().size()) {
                        int ttl = ttls.get(rollupLevelInner);
                        return rollup(agentRollupId, rollupLevelInner, ttl)
                                .thenApply(ignored -> rollupLevelInner + 1)
                                .thenCompose(this);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            };
            return starting.thenApply(ignored -> rollupLevel).thenCompose(lambda);
        });
    }

    private CompletionStage<?> rollup(String agentRollupId, int rollupLevel, int ttl) {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        return Common.getNeedsRollupList(agentRollupId,
                        rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock, rollup)
                .thenCompose(needsRollupCollection -> {
                    Long nextRollupIntervalMillis = null;
                    if (rollupLevel + 1 < rollupConfigs.size()) {
                        nextRollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
                    }
                    final Long finalNextRollupIntervalMillis = nextRollupIntervalMillis;
                    List<NeedsRollup> needsRollupList = new ArrayList<>(needsRollupCollection);
                    int maxIndexNeedsRollupList = needsRollupList.size();

                    Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                        @Override
                        public CompletionStage<?> apply(Integer indexNeedsRollup) {
                            if (indexNeedsRollup >= maxIndexNeedsRollupList) {
                                return CompletableFuture.completedFuture(null);
                            }
                            NeedsRollup needsRollup = needsRollupList.get(indexNeedsRollup);
                            long captureTime = needsRollup.getCaptureTime();
                            long from = captureTime - rollupIntervalMillis;
                            int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
                            Set<String> syntheticMonitorIds = needsRollup.getKeys();
                            List<CompletionStage<?>> futures = new ArrayList<>();
                            for (String syntheticMonitorId : syntheticMonitorIds) {
                                futures.add(rollupOne(rollupLevel, agentRollupId, syntheticMonitorId, from,
                                        captureTime, adjustedTTL));
                            }
                            // wait for above async work to ensure rollup complete before proceeding
                            return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
                                int needsRollupAdjustedTTL =
                                        Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
                                PreparedStatement insertNeedsRollup = finalNextRollupIntervalMillis == null ? null
                                        : SyntheticResultDaoImpl.this.insertNeedsRollup.get(rollupLevel);
                                PreparedStatement deleteNeedsRollup = SyntheticResultDaoImpl.this.deleteNeedsRollup.get(rollupLevel - 1);
                                return Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), syntheticMonitorIds,
                                                needsRollup.getUniquenessKeysForDeletion(), finalNextRollupIntervalMillis,
                                                insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session, rollup)
                                        .thenCompose(ignore -> apply(indexNeedsRollup + 1));

                            });
                        }
                    };

                    return lambda.apply(0);
                });
    }

    // from is non-inclusive
    private CompletableFuture<?> rollupOne(int rollupLevel, String agentRollupId,
                                           String syntheticMonitorId, long from, long to, int adjustedTTL) {
        int i = 0;
        BoundStatement boundStatement = readResultForRollupPS.get(rollupLevel - 1).bind()
                .setString(i++, agentRollupId)
                .setString(i++, syntheticMonitorId)
                .setInstant(i++, Instant.ofEpochMilli(from))
                .setInstant(i++, Instant.ofEpochMilli(to));
        CompletableFuture<AsyncResultSet> future = session.readAsyncWarnIfNoRows(boundStatement, rollup,
                "no synthetic result table records found for agentRollupId={},"
                        + " syntheticMonitorId={}, from={}, to={}, level={}",
                agentRollupId, syntheticMonitorId, from, to, rollupLevel).toCompletableFuture();
        return MoreFutures.rollupAsync(future, asyncExecutor, new MoreFutures.DoRollup() {
            @Override
            public CompletableFuture<?> execute(AsyncResultSet rows) {
                return rollupOneFromRows(rollupLevel, agentRollupId, syntheticMonitorId, to,
                        adjustedTTL, rows);
            }
        });
    }

    private CompletableFuture<?> rollupOneFromRows(int rollupLevel, String agentRollupId,
                                                   String syntheticMonitorId, long to, int adjustedTTL, AsyncResultSet results) {
        DoubleAccumulator totalDurationNanos = new DoubleAccumulator(Double::sum, 0.0);
        AtomicLong executionCount = new AtomicLong(0);
        ErrorIntervalCollector errorIntervalCollector = new ErrorIntervalCollector();

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    int i = 0;
                    totalDurationNanos.accumulate(row.getDouble(i++));
                    executionCount.addAndGet(row.getLong(i++));
                    ByteBuffer errorIntervalsBytes = row.getByteBuffer(i++);
                    synchronized (errorIntervalCollector) {
                        if (errorIntervalsBytes == null) {
                            errorIntervalCollector.addGap();
                        } else {
                            List<Stored.ErrorInterval> errorIntervals = Messages
                                    .parseDelimitedFrom(errorIntervalsBytes, Stored.ErrorInterval.parser());
                            errorIntervalCollector.addErrorIntervals(fromProto(errorIntervals));
                        }
                    }
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return compute.apply(results).thenCompose(res -> {
            int i = 0;
            BoundStatement boundStatement = insertResultPS.get(rollupLevel).bind()
                    .setString(i++, agentRollupId)
                    .setString(i++, syntheticMonitorId)
                    .setInstant(i++, Instant.ofEpochMilli(to))
                    .setDouble(i++, totalDurationNanos.doubleValue())
                    .setLong(i++, executionCount.get());
            List<ErrorInterval> mergedErrorIntervals = errorIntervalCollector.getMergedErrorIntervals();
            if (mergedErrorIntervals.isEmpty()) {
                boundStatement = boundStatement.setToNull(i++);
            } else {
                boundStatement = boundStatement.setByteBuffer(i++, Messages.toByteBuffer(toProto(mergedErrorIntervals)));
            }
            boundStatement = boundStatement.setInt(i++, adjustedTTL);
            return session.writeAsync(boundStatement, rollup).toCompletableFuture();
        });
    }

    private CompletionStage<List<Integer>> getTTLs() {
        return configRepository.getCentralStorageConfig().thenApply(centralStorageConfig -> {
            List<Integer> ttls = new ArrayList<>();
            for (long expirationHours : centralStorageConfig.rollupExpirationHours()) {
                ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
            }
            return ttls;
        });
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
