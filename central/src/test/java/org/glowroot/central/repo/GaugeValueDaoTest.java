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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.glowroot.central.repo.Common.NeedsRollupKey;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GaugeValueDaoTest {

    @Test
    public void rollupContinuesNewerIntervalsAndPropagatesFailureWhenChildHasParent()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 60_000L;
        long t2 = 120_000L;

        TestContext ctx = createContext(
                "gauge_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("gauge1")),
                        mockNeedsRollupRow(t2, uuid2, Set.of("gauge1"))));

        AsyncResultSet secondIntervalResults = resultSet(List.of(mockGaugeRow(42.0, 2L)));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("parent::child1"), eq("gauge1"),
                anyLong(), anyLong(), eq(1)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(secondIntervalResults));

        assertThatThrownBy(() -> ctx.dao.rollup("parent::child1").toCompletableFuture()
                .get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("simulated timeout");

        verify(ctx.session, times(2)).readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("parent::child1"), eq("gauge1"),
                anyLong(), anyLong(), eq(1));

        List<BoundStatement> readStatements = captureReadStatements(ctx.session);
        assertThat(readStatements).anyMatch(bs ->
                ctx.sql(bs).contains("from gauge_needs_rollup_2 "));

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_value_rollup_1 "));

        BoundStatement needsChildInsert = findStatement(writes, ctx,
                "insert into gauge_needs_rollup_from_child");
        assertThat(needsChildInsert).isNotNull();
        verify(needsChildInsert).setString(0, "parent::");
        verify(needsChildInsert).setInstant(1, Instant.ofEpochMilli(t2));
        verify(needsChildInsert).setString(3, "parent::child1");

        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_needs_rollup_2 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from gauge_needs_rollup_1 ");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setInstant(1, Instant.ofEpochMilli(t1));
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    @Test
    public void rollupCompletesNormallyWhenTopLevelLeafIntervalFails() throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 60_000L;
        long t2 = 120_000L;

        TestContext ctx = createContext(
                "gauge_needs_rollup_1",
                List.of(mockNeedsRollupRow(t1, uuid1, Set.of("gauge1")),
                        mockNeedsRollupRow(t2, uuid2, Set.of("gauge1"))));

        AsyncResultSet secondIntervalResults = resultSet(List.of(mockGaugeRow(42.0, 2L)));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("agent1"), eq("gauge1"),
                anyLong(), anyLong(), eq(1)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(secondIntervalResults));

        ctx.dao.rollup("agent1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_value_rollup_1 "));
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_needs_rollup_2 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from gauge_needs_rollup_1 ");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    @Test
    public void rollupFromChildrenContinuesNewerIntervalsAndPropagatesFailureWhenIntermediateRollupHasParent()
            throws Exception {
        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        long t1 = 60_000L;
        long t2 = 120_000L;

        TestContext ctx = createContext(
                "gauge_needs_rollup_from_child",
                List.of(mockNeedsRollupFromChildRow(t1, uuid1, "gp::parent1::child1",
                                Set.of("gauge1")),
                        mockNeedsRollupFromChildRow(t2, uuid2, "gp::parent1::child1",
                                Set.of("gauge1"))));

        AsyncResultSet secondIntervalResults = resultSet(List.of(mockGaugeRow(42.0, 2L)));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("gp::parent1::child1"), eq("gauge1"),
                anyLong(), eq(1)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(secondIntervalResults));

        assertThatThrownBy(() -> ctx.dao.rollup("gp::parent1::").toCompletableFuture()
                .get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("simulated timeout");

        verify(ctx.session, times(2)).readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("gp::parent1::child1"), eq("gauge1"),
                anyLong(), eq(1));

        List<BoundStatement> readStatements = captureReadStatements(ctx.session);
        assertThat(readStatements).anyMatch(bs ->
                ctx.sql(bs).contains("from gauge_needs_rollup_2 "));

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_value_rollup_1 "));

        BoundStatement needsChildInsert = findStatement(writes, ctx,
                "insert into gauge_needs_rollup_from_child");
        assertThat(needsChildInsert).isNotNull();
        verify(needsChildInsert).setString(0, "gp::");
        verify(needsChildInsert).setInstant(1, Instant.ofEpochMilli(t2));
        verify(needsChildInsert).setString(3, "gp::parent1::");

        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_needs_rollup_2 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from gauge_needs_rollup_from_child");
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
                "gauge_needs_rollup_from_child",
                List.of(mockNeedsRollupFromChildRow(t1, uuid1, "parent::child1", Set.of("gauge1")),
                        mockNeedsRollupFromChildRow(t2, uuid2, "parent::child1",
                                Set.of("gauge1"))));

        AsyncResultSet secondIntervalResults = resultSet(List.of(mockGaugeRow(42.0, 2L)));
        when(ctx.session.readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("parent::child1"), eq("gauge1"),
                anyLong(), eq(1)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(secondIntervalResults));

        ctx.dao.rollup("parent::").toCompletableFuture().get(2, TimeUnit.SECONDS);

        List<BoundStatement> writes = captureWriteStatements(ctx.session);
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_value_rollup_1 "));
        assertThat(writes).anyMatch(bs ->
                ctx.sql(bs).startsWith("insert into gauge_needs_rollup_2 "));

        List<BoundStatement> deletes = findStatements(writes, ctx,
                "delete from gauge_needs_rollup_from_child");
        assertThat(deletes).hasSize(1);
        verify(deletes.get(0)).setInstant(1, Instant.ofEpochMilli(t2));
        verify(deletes.get(0)).setUuid(2, uuid2);
        verify(deletes.get(0), never()).setUuid(2, uuid1);
    }

    private static TestContext createContext(String needsRollupTableFragment,
            List<Row> needsRollupRows) throws Exception {
        Session session = mock(Session.class);
        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getRollupConfigs())
                .thenReturn(ImmutableList.copyOf(RollupConfig.buildRollupConfigs()));
        when(configRepository.getCentralStorageConfig()).thenAnswer(ignored ->
                CompletableFuture.completedFuture(ImmutableCentralStorageConfig.builder().build()));

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

        when(session.writeAsync(any(BoundStatement.class), any(CassandraProfile.class)))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(emptyResults));

        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return 1_000_000L;
            }
        };

        GaugeValueDaoImpl dao = new GaugeValueDaoImpl(session, configRepository, clusterManager,
                MoreExecutors.newDirectExecutorService(), 0, clock);
        return new TestContext(dao, session, sqlByPS);
    }

    private static List<BoundStatement> captureReadStatements(Session session) {
        ArgumentCaptor<BoundStatement> captor = ArgumentCaptor.forClass(BoundStatement.class);
        verify(session, Mockito.atLeastOnce()).readAsync(captor.capture(),
                eq(CassandraProfile.rollup));
        return captor.getAllValues();
    }

    private static List<BoundStatement> captureWriteStatements(Session session) {
        ArgumentCaptor<BoundStatement> captor = ArgumentCaptor.forClass(BoundStatement.class);
        verify(session, Mockito.atLeastOnce()).writeAsync(captor.capture(),
                any(CassandraProfile.class));
        return captor.getAllValues();
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

    private static Row mockGaugeRow(double value, long weight) {
        Row row = mock(Row.class);
        when(row.getDouble(0)).thenReturn(value);
        when(row.getLong(1)).thenReturn(weight);
        return row;
    }

    private static AsyncResultSet resultSet(List<Row> rows) {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(rows);
        when(resultSet.hasMorePages()).thenReturn(false);
        return resultSet;
    }

    private static final class TestContext {
        private final GaugeValueDaoImpl dao;
        private final Session session;
        private final Map<PreparedStatement, String> sqlByPS;

        private TestContext(GaugeValueDaoImpl dao, Session session,
                Map<PreparedStatement, String> sqlByPS) {
            this.dao = dao;
            this.session = session;
            this.sqlByPS = sqlByPS;
        }

        private String sql(BoundStatement bs) {
            String sql = sqlByPS.get(bs.getPreparedStatement());
            return sql == null ? "" : sql;
        }
    }
}
