/*
 * Copyright 2026 the original author or authors.
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.glowroot.central.repo.Common.NeedsRollupKey;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AggregateDaoTest {

    @Test
    public void rollupFromChildrenContinuesNewerIntervalsAndPropagatesFailureWhenIntermediateRollupHasParent()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 60_000L;
        long t2 = 120_000L;

        TestContext ctx = createContext(
                "aggregate_needs_rollup_from_child",
                List.of(mockNeedsRollupFromChildRow(t1, uuid1, "gp::parent1::child1",
                                Set.of("Web")),
                        mockNeedsRollupFromChildRow(t2, uuid2, "gp::parent1::child1",
                                Set.of("Web"))));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));

        assertThatThrownBy(() -> ctx.dao.rollup("gp::parent1::").toCompletableFuture()
                .get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("simulated timeout");

        List<BoundStatement> readStatements = captureReadStatements(ctx.session);
        assertThat(readStatements).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_needs_rollup_1 "));

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        BoundStatement needsChildInsert = findStatement(writes, ctx,
                "insert into aggregate_needs_rollup_from_child");
        assertThat(needsChildInsert).isNotNull();
        verify(needsChildInsert).setString(0, "gp::");
        verify(needsChildInsert).setInstant(1, Instant.ofEpochMilli(t2));
        verify(needsChildInsert).setString(3, "gp::parent1::");

        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into aggregate_needs_rollup_1 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from aggregate_needs_rollup_from_child");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    @Test
    public void rollupFromChildrenCompletesNormallyWhenTopLevelRollupIntervalFails()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 60_000L;
        long t2 = 120_000L;

        TestContext ctx = createContext(
                "aggregate_needs_rollup_from_child",
                List.of(mockNeedsRollupFromChildRow(t1, uuid1, "parent::child1", Set.of("Web")),
                        mockNeedsRollupFromChildRow(t2, uuid2, "parent::child1", Set.of("Web"))));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));

        ctx.dao.rollup("parent::").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into aggregate_needs_rollup_1 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from aggregate_needs_rollup_from_child");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    @Test
    public void rollupLeafCompletesNormallyWhenLevel1IntervalFails() throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 300_000L;
        long t2 = 600_000L;

        TestContext ctx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web")),
                        mockNeedsRollupRow(t2, uuid2, Set.of("Web"))));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));

        ctx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into aggregate_needs_rollup_2 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from aggregate_needs_rollup_1 ");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    @Test
    public void rollupLeafContinuesHigherLevelsAndCompletesNormallyWhenNeedsRollup1ReadFails()
            throws Exception {
        TestContext ctx = createContext("aggregate_needs_rollup_1", Collections.emptyList());

        when(ctx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    BoundStatement bs = invocation.getArgument(0);
                    if (ctx.sql(bs).contains("from aggregate_needs_rollup_1 ")) {
                        return CompletableFuture.failedFuture(
                                new RuntimeException("needs_rollup_1 read timeout"));
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });

        ctx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> readStatements = captureReadStatements(ctx.session);
        assertThat(readStatements).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_needs_rollup_2 "));
        assertThat(readStatements).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_needs_rollup_3 "));
    }

    @Test
    public void rollupLeafSkipsEmptyOptionalPerTransactionTablesAndRollsUpPresentOnes()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        long t1 = 300_000L;

        TestContext ctx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web"))));

        AsyncResultSet txSummaryResults = resultSet(List.of(
                mockTransactionSummaryRow("/txn1"),
                mockTransactionSummaryRow("/txn2")));
        AsyncResultSet queryResults = resultSet(List.of(mockQueryRow()));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));
        when(ctx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_needs_rollup_1 ")) {
                        return CompletableFuture.completedFuture(
                                resultSet(List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web")))));
                    }
                    if (sql.contains("from aggregate_tn_summary_rollup_0 ")) {
                        return CompletableFuture.completedFuture(txSummaryResults);
                    }
                    if (sql.contains("from aggregate_tt_query_rollup_0 ")
                            || sql.contains("from aggregate_tn_query_rollup_0 ")) {
                        return CompletableFuture.completedFuture(queryResults);
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });

        ctx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> reads = captureReadStatements(ctx.session);
        assertThat(reads).anyMatch(bs -> ctx.sql(bs).contains("from aggregate_tt_query_rollup_0 "));
        assertThat(reads).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tt_service_call_rollup_0 "));
        assertThat(reads).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tt_main_thread_profile_rollup_0 "));
        assertThat(reads).anyMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tt_aux_thread_profile_rollup_0 "));

        assertThat(reads.stream()
                .filter(bs -> ctx.sql(bs).contains("from aggregate_tn_query_rollup_0 "))
                .count()).isEqualTo(2);
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_service_call_rollup_0 "));
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_main_thread_profile_rollup_0 "));
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_aux_thread_profile_rollup_0 "));

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into aggregate_tt_query_rollup_1 "));
        assertThat(writes.stream()
                .filter(bs -> ctx.sql(bs).startsWith("insert into aggregate_tn_query_rollup_1 "))
                .count()).isEqualTo(2);
    }

    @Test
    public void rollupFromChildrenSkipsEmptyOptionalPerTransactionTablesAndRollsUpPresentOnes()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        long t1 = 60_000L;

        TestContext ctx = createContext(
                "aggregate_needs_rollup_from_child",
                List.of(mockNeedsRollupFromChildRow(t1, uuid1, "parent::child1", Set.of("Web"))));

        AsyncResultSet txSummaryResults = resultSet(List.of(
                mockTransactionSummaryRow("/txn1"),
                mockTransactionSummaryRow("/txn2")));
        AsyncResultSet profileResults = resultSet(List.of(mockProfileRow()));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_tn_summary_rollup_0 ")) {
                        return CompletableFuture.completedFuture(txSummaryResults);
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });
        when(ctx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_needs_rollup_from_child")) {
                        return CompletableFuture.completedFuture(resultSet(List.of(
                                mockNeedsRollupFromChildRow(t1, uuid1, "parent::child1",
                                        Set.of("Web")))));
                    }
                    if (sql.contains("from aggregate_tt_main_thread_profile_rollup_0 ")
                            || sql.contains("from aggregate_tn_main_thread_profile_rollup_0 ")) {
                        return CompletableFuture.completedFuture(profileResults);
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });

        ctx.dao.rollup("parent::").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> reads = captureReadStatements(ctx.session);
        assertThat(reads.stream()
                .filter(bs -> ctx.sql(bs)
                        .contains("from aggregate_tn_main_thread_profile_rollup_0 "))
                .count()).isEqualTo(2);
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_query_rollup_0 "));
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_service_call_rollup_0 "));
        assertThat(reads).noneMatch(bs ->
                ctx.sql(bs).contains("from aggregate_tn_aux_thread_profile_rollup_0 "));

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into aggregate_tt_main_thread_profile_rollup_0 "));
        assertThat(writes.stream()
                .filter(bs -> ctx.sql(bs)
                        .startsWith("insert into aggregate_tn_main_thread_profile_rollup_0 "))
                .count()).isEqualTo(2);
    }

    @Test
    public void rollupSkipsExpiredDetailTablesWhilePreservingGeneralRollupsAndZeroTtl()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        long currentTimeMillis = TimeUnit.DAYS.toMillis(10);
        // 8 days ago: expired for 7-day level-1 query/serviceCall TTL, still live for 14-day
        // general TTL and 14-day profile TTL
        long captureTime = TimeUnit.DAYS.toMillis(2);

        CentralStorageConfig storageConfig = ImmutableCentralStorageConfig.builder()
                .rollupExpirationHours(ImmutableList.of(48, 24 * 14, 24 * 90, 24 * 365))
                .queryAndServiceCallRollupExpirationHours(
                        ImmutableList.of(48, 24 * 7, 24 * 30, 24 * 30))
                .profileRollupExpirationHours(ImmutableList.of(48, 24 * 14, 24 * 30, 24 * 30))
                .build();

        TestContext expiredCtx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(captureTime, uuid1, Set.of("Web"))),
                storageConfig,
                currentTimeMillis);

        AsyncResultSet overallSummaryResults = resultSet(List.of(mockOverallSummaryRow()));
        AsyncResultSet txSummaryResults = resultSet(List.of(mockTransactionSummaryRow("/txn1")));

        when(expiredCtx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = expiredCtx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_tt_summary_rollup_0 ")) {
                        return CompletableFuture.completedFuture(overallSummaryResults);
                    }
                    return CompletableFuture.completedFuture(expiredCtx.emptyResults);
                });
        when(expiredCtx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String sql = expiredCtx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_needs_rollup_1 ")) {
                        return CompletableFuture.completedFuture(resultSet(
                                List.of(mockNeedsRollupRow(captureTime, uuid1, Set.of("Web")))));
                    }
                    if (sql.contains("from aggregate_tn_summary_rollup_0 ")) {
                        return CompletableFuture.completedFuture(txSummaryResults);
                    }
                    return CompletableFuture.completedFuture(expiredCtx.emptyResults);
                });

        expiredCtx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> expiredReads = captureReadStatements(expiredCtx.session);
        assertThat(expiredReads).noneMatch(bs ->
                expiredCtx.sql(bs).contains("from aggregate_tt_query_rollup_0 ")
                        || expiredCtx.sql(bs).contains("from aggregate_tn_query_rollup_0 ")
                        || expiredCtx.sql(bs).contains("from aggregate_tt_service_call_rollup_0 ")
                        || expiredCtx.sql(bs).contains("from aggregate_tn_service_call_rollup_0 "));
        // 14-day profile TTL has not expired at 8 days old, so overall profile tables are still
        // checked (and tn_ profile tables skipped because overall had no rows)
        assertThat(expiredReads).anyMatch(bs ->
                expiredCtx.sql(bs).contains("from aggregate_tt_main_thread_profile_rollup_0 "));
        assertThat(expiredReads).noneMatch(bs ->
                expiredCtx.sql(bs).contains("from aggregate_tn_main_thread_profile_rollup_0 "));

        List<BoundStatement> expiredWrites = captureWriteStatements(expiredCtx.session);
        assertThat(expiredWrites).anyMatch(bs ->
                expiredCtx.sql(bs).startsWith("insert into aggregate_tt_summary_rollup_1 "));
        assertThat(expiredWrites).anyMatch(bs ->
                expiredCtx.sql(bs).startsWith("insert into aggregate_tn_summary_rollup_1 "));

        // Verify expirationHours = 0 (infinite retention) never skips detail tables
        CentralStorageConfig zeroTtlConfig = ImmutableCentralStorageConfig.builder()
                .rollupExpirationHours(ImmutableList.of(0, 0, 0, 0))
                .queryAndServiceCallRollupExpirationHours(ImmutableList.of(0, 0, 0, 0))
                .profileRollupExpirationHours(ImmutableList.of(0, 0, 0, 0))
                .build();
        TestContext zeroTtlCtx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(captureTime, uuid1, Set.of("Web"))),
                zeroTtlConfig,
                TimeUnit.DAYS.toMillis(400));
        when(zeroTtlCtx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(zeroTtlCtx.emptyResults));

        zeroTtlCtx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> zeroTtlReads = captureReadStatements(zeroTtlCtx.session);
        assertThat(zeroTtlReads).anyMatch(bs ->
                zeroTtlCtx.sql(bs).contains("from aggregate_tt_query_rollup_0 "));
        assertThat(zeroTtlReads).anyMatch(bs ->
                zeroTtlCtx.sql(bs).contains("from aggregate_tt_service_call_rollup_0 "));
        assertThat(zeroTtlReads).anyMatch(bs ->
                zeroTtlCtx.sql(bs).contains("from aggregate_tt_main_thread_profile_rollup_0 "));
        assertThat(zeroTtlReads).anyMatch(bs ->
                zeroTtlCtx.sql(bs).contains("from aggregate_tt_aux_thread_profile_rollup_0 "));
    }

    @Test
    public void rollupBoundsPerTransactionConcurrencyToFour() throws Exception {
        assertThat(AggregateDaoImpl.MAX_CONCURRENT_PER_TRANSACTION_ROLLUPS).isEqualTo(4);

        UUID uuid1 = Uuids.timeBased();
        long t1 = 300_000L;
        List<Row> summaryRows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            summaryRows.add(mockTransactionSummaryRow("/txn" + i));
        }

        TestContext ctx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web"))));

        AtomicInteger inFlightTransactions = new AtomicInteger();
        AtomicInteger maxInFlightTransactions = new AtomicInteger();
        AtomicInteger totalTransactionOverviewReads = new AtomicInteger();
        Queue<CompletableFuture<AsyncResultSet>> pendingOverviewReads = new ArrayDeque<>();

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_tn_overview_rollup_0 ")) {
                        int active = inFlightTransactions.incrementAndGet();
                        maxInFlightTransactions.accumulateAndGet(active, Math::max);
                        totalTransactionOverviewReads.incrementAndGet();
                        CompletableFuture<AsyncResultSet> pending = new CompletableFuture<>();
                        pendingOverviewReads.add(pending);
                        return pending;
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });
        when(ctx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_needs_rollup_1 ")) {
                        return CompletableFuture.completedFuture(
                                resultSet(List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web")))));
                    }
                    if (sql.contains("from aggregate_tn_summary_rollup_0 ")) {
                        return CompletableFuture.completedFuture(resultSet(summaryRows));
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });

        CompletableFuture<?> rollupFuture = ctx.dao.rollup("parent::child1").toCompletableFuture();
        assertThat(pendingOverviewReads).hasSize(4);
        assertThat(inFlightTransactions.get()).isEqualTo(4);

        while (!pendingOverviewReads.isEmpty()) {
            CompletableFuture<AsyncResultSet> next = pendingOverviewReads.poll();
            inFlightTransactions.decrementAndGet();
            next.complete(ctx.emptyResults);
        }

        rollupFuture.get(2, TimeUnit.SECONDS);
        assertThat(totalTransactionOverviewReads.get()).isEqualTo(10);
        assertThat(maxInFlightTransactions.get()).isEqualTo(4);
    }

    @Test
    public void rollupAndStoreBatchSamePartitionQueriesAndServiceCallsAndChunkLargeBatches()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 300_000L;

        // 2 needs_rollup uniqueness rows for the same interval t1
        TestContext ctx = createContext(
                "aggregate_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("Web")),
                        mockNeedsRollupRow(t1, uuid2, Set.of("Background"))));

        List<Row> thirtyQueryRows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            thirtyQueryRows.add(mockQueryRow("select col from tbl_" + i + " where id = ? and "
                    + "padding_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx = ?"));
        }
        AsyncResultSet queryResults = resultSet(thirtyQueryRows);
        AsyncResultSet serviceCallResults = resultSet(List.of(
                mockServiceCallRow("HTTP", "GET https://svc-a/items"),
                mockServiceCallRow("HTTP", "POST https://svc-b/orders"),
                mockServiceCallRow("HTTP", "GET https://svc-c/users")));

        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ctx.emptyResults));
        when(ctx.session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String sql = ctx.sql(invocation.getArgument(0));
                    if (sql.contains("from aggregate_needs_rollup_1 ")) {
                        return CompletableFuture.completedFuture(resultSet(List.of(
                                mockNeedsRollupRow(t1, uuid1, Set.of("Web")),
                                mockNeedsRollupRow(t1, uuid2, Set.of("Background")))));
                    }
                    if (sql.contains("from aggregate_tt_query_rollup_0 ")) {
                        return CompletableFuture.completedFuture(queryResults);
                    }
                    if (sql.contains("from aggregate_tt_service_call_rollup_0 ")) {
                        return CompletableFuture.completedFuture(serviceCallResults);
                    }
                    return CompletableFuture.completedFuture(ctx.emptyResults);
                });

        ctx.dao.rollup("parent::child1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<Statement<?>> rawRollupWrites =
                captureRawWriteStatements(ctx.session, CassandraProfile.rollup);

        // 30 large queries per transactionType (2 transactionTypes = 60 rows) are chunked into
        // multiple UNLOGGED batches (fewer writes than 60 individual statements, more than 2)
        List<BatchStatement> queryBatches = findBatchStatements(rawRollupWrites, ctx,
                "insert into aggregate_tt_query_rollup_1 ");
        assertThat(queryBatches.size()).isGreaterThan(2).isLessThan(10);
        int totalQueryStatements = 0;
        for (BatchStatement batch : queryBatches) {
            assertThat(batch.getBatchType()).isEqualTo(DefaultBatchType.UNLOGGED);
            totalQueryStatements += Iterables.size(batch);
        }
        assertThat(totalQueryStatements).isEqualTo(60);

        // 3 service calls per transactionType fit in 1 UNLOGGED batch per transactionType
        List<BatchStatement> serviceCallBatches = findBatchStatements(rawRollupWrites, ctx,
                "insert into aggregate_tt_service_call_rollup_1 ");
        assertThat(serviceCallBatches).hasSize(2);
        for (BatchStatement batch : serviceCallBatches) {
            assertThat(batch.getBatchType()).isEqualTo(DefaultBatchType.UNLOGGED);
            assertThat(Iterables.size(batch)).isEqualTo(3);
        }

        // 2 needs_rollup_1 deletes at t1 are batched into 1 UNLOGGED batch
        List<BatchStatement> deleteBatches = findBatchStatements(rawRollupWrites, ctx,
                "delete from aggregate_needs_rollup_1 ");
        assertThat(deleteBatches).hasSize(1);
        assertThat(deleteBatches.get(0).getBatchType()).isEqualTo(DefaultBatchType.UNLOGGED);
        assertThat(Iterables.size(deleteBatches.get(0))).isEqualTo(2);

        // Also verify collector store() batches multiple queries and service calls
        when(ctx.activeAgentDao.insert(anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(ctx.transactionTypeDao.store(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Aggregate overallAggregate = Aggregate.newBuilder()
                .setTotalDurationNanos(1000.0)
                .setTransactionCount(2)
                .addQuery(Aggregate.Query.newBuilder()
                        .setType("SQL")
                        .setSharedQueryTextIndex(0)
                        .setTotalDurationNanos(500.0)
                        .setExecutionCount(1))
                .addQuery(Aggregate.Query.newBuilder()
                        .setType("SQL")
                        .setSharedQueryTextIndex(1)
                        .setTotalDurationNanos(300.0)
                        .setExecutionCount(1))
                .addServiceCall(Aggregate.ServiceCall.newBuilder()
                        .setType("HTTP")
                        .setText("GET /a")
                        .setTotalDurationNanos(200.0)
                        .setExecutionCount(1))
                .addServiceCall(Aggregate.ServiceCall.newBuilder()
                        .setType("HTTP")
                        .setText("GET /b")
                        .setTotalDurationNanos(100.0)
                        .setExecutionCount(1))
                .build();
        List<Aggregate.SharedQueryText> sharedQueryTexts = List.of(
                Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build(),
                Aggregate.SharedQueryText.newBuilder().setFullText("select 2").build());
        OldAggregatesByType aggregatesByType = OldAggregatesByType.newBuilder()
                .setTransactionType("Web")
                .setOverallAggregate(overallAggregate)
                .build();

        ctx.dao.store("agent1", 60_000L, List.of(aggregatesByType), sharedQueryTexts)
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<Statement<?>> rawCollectorWrites =
                captureRawWriteStatements(ctx.session, CassandraProfile.collector);
        List<BatchStatement> collectorQueryBatches = findBatchStatements(rawCollectorWrites, ctx,
                "insert into aggregate_tt_query_rollup_0 ");
        assertThat(collectorQueryBatches).hasSize(1);
        assertThat(collectorQueryBatches.get(0).getBatchType())
                .isEqualTo(DefaultBatchType.UNLOGGED);
        assertThat(Iterables.size(collectorQueryBatches.get(0))).isEqualTo(2);

        List<BatchStatement> collectorServiceCallBatches = findBatchStatements(rawCollectorWrites,
                ctx, "insert into aggregate_tt_service_call_rollup_0 ");
        assertThat(collectorServiceCallBatches).hasSize(1);
        assertThat(collectorServiceCallBatches.get(0).getBatchType())
                .isEqualTo(DefaultBatchType.UNLOGGED);
        assertThat(Iterables.size(collectorServiceCallBatches.get(0))).isEqualTo(2);
    }

    private static TestContext createContext(String needsRollupTableFragment,
            List<Row> needsRollupRows) throws Exception {
        return createContext(needsRollupTableFragment, needsRollupRows,
                ImmutableCentralStorageConfig.builder().build(), 1_000_000L);
    }

    private static TestContext createContext(String needsRollupTableFragment,
            List<Row> needsRollupRows, CentralStorageConfig storageConfig, long currentTimeMillis)
            throws Exception {
        Session session = mock(Session.class);
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        TransactionTypeDao transactionTypeDao = mock(TransactionTypeDao.class);
        FullQueryTextDao fullQueryTextDao = mock(FullQueryTextDao.class);

        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getRollupConfigs())
                .thenReturn(ImmutableList.copyOf(RollupConfig.buildRollupConfigs()));
        when(configRepository.getCentralStorageConfig()).thenAnswer(ignored ->
                CompletableFuture.completedFuture(storageConfig));
        when(configRepository.getAdvancedConfig(anyString())).thenAnswer(ignored ->
                CompletableFuture.completedFuture(AdvancedConfig.getDefaultInstance()));

        ClusterManager clusterManager = mock(ClusterManager.class);
        when(clusterManager.<NeedsRollupKey, ImmutableSet<String>>createReplicatedMap(
                anyString(), anyLong(), any()))
                .thenAnswer(ignored -> new ConcurrentHashMap<NeedsRollupKey, ImmutableSet<String>>());

        Map<PreparedStatement, String> sqlByPS = new HashMap<>();
        when(session.prepare(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            sqlByPS.put(ps, sql);
            when(ps.bind()).thenAnswer(bindInvocation -> mock(BoundStatement.class, bsInvocation -> {
                if ("getPreparedStatement".equals(bsInvocation.getMethod().getName())) {
                    return ps;
                }
                if (bsInvocation.getMethod().getReturnType()
                        .isAssignableFrom(BoundStatement.class)) {
                    return bsInvocation.getMock();
                }
                return Mockito.RETURNS_DEFAULTS.answer(bsInvocation);
            }));
            return ps;
        });

        AsyncResultSet needsRollupResults = resultSet(needsRollupRows);
        AsyncResultSet emptyResults = resultSet(Collections.emptyList());

        when(session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    BoundStatement bs = invocation.getArgument(0);
                    String sql = sqlByPS.get(bs.getPreparedStatement());
                    if (sql != null && sql.contains(needsRollupTableFragment)) {
                        return CompletableFuture.completedFuture(needsRollupResults);
                    }
                    return CompletableFuture.completedFuture(emptyResults);
                });

        when(session.writeAsync(any(Statement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(emptyResults));
        when(session.writeAsync(any(Statement.class), eq(CassandraProfile.collector)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(emptyResults));

        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return currentTimeMillis;
            }
        };

        AggregateDaoImpl dao = new AggregateDaoImpl(session, activeAgentDao, transactionTypeDao,
                fullQueryTextDao, configRepository, clusterManager, Runnable::run, 0, clock);
        return new TestContext(dao, session, activeAgentDao, transactionTypeDao, sqlByPS,
                emptyResults);
    }

    private static List<BoundStatement> captureReadStatements(Session session) {
        ArgumentCaptor<BoundStatement> captor = ArgumentCaptor.forClass(BoundStatement.class);
        verify(session, Mockito.atLeastOnce()).readAsync(captor.capture(),
                eq(CassandraProfile.rollup));
        return captor.getAllValues();
    }

    private static List<BoundStatement> captureWriteStatements(Session session) {
        List<BoundStatement> flattened = new ArrayList<>();
        for (Statement<?> stmt : captureRawWriteStatements(session, CassandraProfile.rollup)) {
            if (stmt instanceof BoundStatement) {
                flattened.add((BoundStatement) stmt);
            } else if (stmt instanceof BatchStatement) {
                for (BatchableStatement<?> child : (BatchStatement) stmt) {
                    if (child instanceof BoundStatement) {
                        flattened.add((BoundStatement) child);
                    }
                }
            }
        }
        return flattened;
    }

    private static List<Statement<?>> captureRawWriteStatements(Session session,
            CassandraProfile profile) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Statement<?>> captor = ArgumentCaptor.forClass(Statement.class);
        verify(session, Mockito.atLeastOnce()).writeAsync(captor.capture(), eq(profile));
        return captor.getAllValues();
    }

    private static List<BatchStatement> findBatchStatements(List<Statement<?>> statements,
            TestContext ctx, String sqlPrefix) {
        List<BatchStatement> matching = new ArrayList<>();
        for (Statement<?> stmt : statements) {
            if (stmt instanceof BatchStatement) {
                BatchStatement batch = (BatchStatement) stmt;
                BatchableStatement<?> first = Iterables.getFirst(batch, null);
                if (first instanceof BoundStatement
                        && ctx.sql((BoundStatement) first).startsWith(sqlPrefix)) {
                    matching.add(batch);
                }
            }
        }
        return matching;
    }

    private static BoundStatement findStatement(List<BoundStatement> statements, TestContext ctx,
            String sqlPrefix) {
        for (BoundStatement statement : statements) {
            if (ctx.sql(statement).startsWith(sqlPrefix)) {
                return statement;
            }
        }
        return null;
    }

    private static List<BoundStatement> findStatements(List<BoundStatement> statements,
            TestContext ctx, String sqlPrefix) {
        List<BoundStatement> matching = new ArrayList<>();
        for (BoundStatement statement : statements) {
            if (ctx.sql(statement).startsWith(sqlPrefix)) {
                matching.add(statement);
            }
        }
        return matching;
    }

    private static Row mockNeedsRollupRow(long captureTime, UUID uniqueness, Set<String> keys) {
        Row row = mock(Row.class);
        when(row.getInstant(0)).thenReturn(Instant.ofEpochMilli(captureTime));
        when(row.getUuid(1)).thenReturn(uniqueness);
        when(row.getSet(2, String.class)).thenReturn(keys);
        return row;
    }

    private static Row mockNeedsRollupFromChildRow(long captureTime, UUID uniqueness,
            String childAgentRollupId, Set<String> keys) {
        Row row = mock(Row.class);
        when(row.getInstant(0)).thenReturn(Instant.ofEpochMilli(captureTime));
        when(row.getUuid(1)).thenReturn(uniqueness);
        when(row.getString(2)).thenReturn(childAgentRollupId);
        when(row.getSet(3, String.class)).thenReturn(keys);
        return row;
    }

    private static Row mockOverallSummaryRow() {
        Row row = mock(Row.class);
        when(row.getDouble(0)).thenReturn(1000.0);
        when(row.getDouble(1)).thenReturn(200.0);
        when(row.getDouble(2)).thenReturn(512.0);
        when(row.getLong(3)).thenReturn(5L);
        return row;
    }

    private static Row mockTransactionSummaryRow(String transactionName) {
        Row row = mock(Row.class);
        when(row.getString(0)).thenReturn(transactionName);
        when(row.getDouble(1)).thenReturn(1000.0);
        when(row.getDouble(2)).thenReturn(200.0);
        when(row.getDouble(3)).thenReturn(512.0);
        when(row.getLong(4)).thenReturn(5L);
        return row;
    }

    private static Row mockQueryRow() {
        return mockQueryRow("select 1");
    }

    private static Row mockQueryRow(String truncatedText) {
        Row row = mock(Row.class);
        when(row.getString(0)).thenReturn("SQL");
        when(row.getString(1)).thenReturn(truncatedText);
        when(row.getString(2)).thenReturn("");
        when(row.getDouble(3)).thenReturn(1000.0);
        when(row.getLong(4)).thenReturn(2L);
        when(row.isNull(5)).thenReturn(true);
        when(row.getLong(5)).thenReturn(0L);
        return row;
    }

    private static Row mockServiceCallRow(String type, String text) {
        Row row = mock(Row.class);
        when(row.getString(0)).thenReturn(type);
        when(row.getString(1)).thenReturn(text);
        when(row.getDouble(2)).thenReturn(500.0);
        when(row.getLong(3)).thenReturn(1L);
        return row;
    }

    private static Row mockProfileRow() {
        byte[] bytes = Profile.getDefaultInstance().toByteArray();
        Row row = mock(Row.class);
        when(row.getByteBuffer(0)).thenAnswer(ignored -> ByteBuffer.wrap(bytes));
        return row;
    }

    private static AsyncResultSet resultSet(List<Row> rows) {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(rows);
        when(resultSet.hasMorePages()).thenReturn(false);
        return resultSet;
    }

    private static final class TestContext {
        private final AggregateDaoImpl dao;
        private final Session session;
        private final ActiveAgentDao activeAgentDao;
        private final TransactionTypeDao transactionTypeDao;
        private final Map<PreparedStatement, String> sqlByPS;
        private final AsyncResultSet emptyResults;

        private TestContext(AggregateDaoImpl dao, Session session, ActiveAgentDao activeAgentDao,
                TransactionTypeDao transactionTypeDao, Map<PreparedStatement, String> sqlByPS,
                AsyncResultSet emptyResults) {
            this.dao = dao;
            this.session = session;
            this.activeAgentDao = activeAgentDao;
            this.transactionTypeDao = transactionTypeDao;
            this.sqlByPS = sqlByPS;
            this.emptyResults = emptyResults;
        }

        private String sql(BoundStatement bs) {
            String sql = sqlByPS.get(bs.getPreparedStatement());
            return sql == null ? "" : sql;
        }
    }
}
