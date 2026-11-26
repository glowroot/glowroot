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
import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.Common.NeedsRollupKey;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CommonTest {

    @Test
    public void getNeedsRollupListDoesNotFetchNextPageAfterReachingRecentRow() throws Exception {
        long rollupIntervalMillis = 60_000L;
        AtomicLong currentTimeMillis = new AtomicLong(1_000_000L);
        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return currentTimeMillis.get();
            }
        };

        Row oldRow1 = mockRow(600_000L, Set.of("Web"));
        Row oldRow2 = mockRow(660_000L, Set.of("Background"));
        Row recentRow = mock(Row.class);
        when(recentRow.getInstant(0)).thenReturn(Instant.ofEpochMilli(960_000L));

        AsyncResultSet page2 = mock(AsyncResultSet.class);
        when(page2.currentPage()).thenReturn(List.of(oldRow2, recentRow));
        when(page2.hasMorePages()).thenReturn(true);

        AsyncResultSet page1 = mock(AsyncResultSet.class);
        when(page1.currentPage()).thenReturn(List.of(oldRow1));
        when(page1.hasMorePages()).thenReturn(true);
        when(page1.fetchNextPage()).thenReturn(CompletableFuture.completedFuture(page2));

        PreparedStatement readNeedsRollup1 = mock(PreparedStatement.class);
        BoundStatement boundStatement = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(readNeedsRollup1.bind()).thenReturn(boundStatement);

        Session session = mock(Session.class);
        when(session.readAsync(boundStatement, CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(page1));

        Collection<NeedsRollup> result = Common.getNeedsRollupList("agent1", 1,
                rollupIntervalMillis, List.of(readNeedsRollup1), session, clock,
                CassandraProfile.rollup).toCompletableFuture().get();

        assertThat(result).extracting(NeedsRollup::getCaptureTime)
                .containsExactly(600_000L, 660_000L);
        verify(page1).fetchNextPage();
        verify(page2, never()).fetchNextPage();
    }

    @Test
    public void aggregateDaoStoreSkipsDuplicateNeedsRollup1InsertsWithinSameRollupWindow()
            throws Exception {
        ImmutableList<RollupConfig> rollupConfigs =
                ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        long intervalMillis = rollupConfigs.get(1).intervalMillis();
        long rollupCaptureTime = CaptureTimes.getRollup(60_000L, intervalMillis);

        PreparedStatement insertNeedsRollup1PS = mock(PreparedStatement.class);
        BoundStatement insertNeedsRollup1BS = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(insertNeedsRollup1PS.bind()).thenReturn(insertNeedsRollup1BS);

        PreparedStatement otherPS = mock(PreparedStatement.class);
        BoundStatement otherBS = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(otherPS.bind()).thenReturn(otherBS);

        Session session = mock(Session.class);
        when(session.prepare(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.startsWith("insert into aggregate_needs_rollup_1 ")) {
                return insertNeedsRollup1PS;
            }
            return otherPS;
        });
        when(session.writeAsync(any(), any()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        when(activeAgentDao.insert(anyString(), anyLong()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        TransactionTypeDao transactionTypeDao = mock(TransactionTypeDao.class);
        when(transactionTypeDao.store(anyList(), anyString()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        FullQueryTextDao fullQueryTextDao = mock(FullQueryTextDao.class);

        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        when(configRepository.getCentralStorageConfig()).thenAnswer(ignored ->
                CompletableFuture.completedFuture(ImmutableCentralStorageConfig.builder().build()));

        ClusterManager clusterManager = mock(ClusterManager.class);
        when(clusterManager.<NeedsRollupKey, ImmutableSet<String>>createReplicatedMap(
                anyString(), anyLong(), any()))
                .thenAnswer(ignored -> new ConcurrentHashMap<NeedsRollupKey, ImmutableSet<String>>());

        AtomicLong currentTimeMillis = new AtomicLong(120_000L);
        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return currentTimeMillis.get();
            }
        };

        AggregateDaoImpl aggregateDao = new AggregateDaoImpl(session, activeAgentDao,
                transactionTypeDao, fullQueryTextDao, configRepository, clusterManager,
                mock(Executor.class), 0, clock);

        OldAggregatesByType web = OldAggregatesByType.newBuilder()
                .setTransactionType("Web")
                .setOverallAggregate(Aggregate.getDefaultInstance())
                .build();
        OldAggregatesByType background = OldAggregatesByType.newBuilder()
                .setTransactionType("Background")
                .setOverallAggregate(Aggregate.getDefaultInstance())
                .build();

        // First store populates cache and writes aggregate_needs_rollup_1
        aggregateDao.store("agent1", 60_000L, List.of(web), List.of()).toCompletableFuture().get();
        verify(session, times(1)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // Repeat with same transaction type in same rollup window is skipped
        aggregateDao.store("agent1", 120_000L, List.of(web), List.of()).toCompletableFuture().get();
        verify(session, times(1)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // New transaction type in same rollup window inserts and merges into cache
        aggregateDao.store("agent1", 180_000L, List.of(background), List.of())
                .toCompletableFuture().get();
        verify(session, times(2)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // Previously cached type is still skipped using merged cache set
        aggregateDao.store("agent1", 240_000L, List.of(web), List.of()).toCompletableFuture().get();
        verify(session, times(2)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // Once old enough to rollup, duplicate check no longer suppresses insert
        currentTimeMillis.set(rollupCaptureTime + intervalMillis + 1);
        aggregateDao.store("agent1", 240_000L, List.of(background), List.of())
                .toCompletableFuture().get();
        verify(session, times(3)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // truncateAll clears needsRollupCache1
        aggregateDao.truncateAll();
        currentTimeMillis.set(120_000L);
        aggregateDao.store("agent1", 60_000L, List.of(web), List.of()).toCompletableFuture().get();
        verify(session, times(4)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);
    }

    @Test
    public void gaugeValueDaoStoreRetainsMergedGaugeNamesInCache() throws Exception {
        ImmutableList<RollupConfig> rollupConfigs =
                ImmutableList.copyOf(RollupConfig.buildRollupConfigs());

        PreparedStatement insertNeedsRollup1PS = mock(PreparedStatement.class);
        BoundStatement insertNeedsRollup1BS = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(insertNeedsRollup1PS.bind()).thenReturn(insertNeedsRollup1BS);

        PreparedStatement otherPS = mock(PreparedStatement.class);
        BoundStatement otherBS = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(otherPS.bind()).thenReturn(otherBS);

        Session session = mock(Session.class);
        when(session.prepare(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.startsWith("insert into gauge_needs_rollup_1 ")) {
                return insertNeedsRollup1PS;
            }
            return otherPS;
        });
        when(session.writeAsync(any(), any()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getRollupConfigs()).thenReturn(rollupConfigs);
        when(configRepository.getCentralStorageConfig()).thenAnswer(ignored ->
                CompletableFuture.completedFuture(ImmutableCentralStorageConfig.builder().build()));

        ClusterManager clusterManager = mock(ClusterManager.class);
        when(clusterManager.<NeedsRollupKey, ImmutableSet<String>>createReplicatedMap(
                anyString(), anyLong(), any()))
                .thenAnswer(ignored -> new ConcurrentHashMap<NeedsRollupKey, ImmutableSet<String>>());

        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return 10_000L;
            }
        };

        GaugeValueDaoImpl gaugeValueDao = new GaugeValueDaoImpl(session, configRepository,
                clusterManager, mock(ExecutorService.class), 0, clock);

        gaugeValueDao.store("agent1", List.of(gauge("g1", 10_000L))).toCompletableFuture().get();
        verify(session, times(1)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // Merges {"g1"} + {"g2"} into cache
        gaugeValueDao.store("agent1", List.of(gauge("g2", 20_000L))).toCompletableFuture().get();
        verify(session, times(2)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // Must remember "g1" from merged cache and skip duplicate insert
        gaugeValueDao.store("agent1", List.of(gauge("g1", 30_000L))).toCompletableFuture().get();
        verify(session, times(2)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);

        // truncateAll clears needsRollupCache1
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("agent1", List.of(gauge("g1", 10_000L))).toCompletableFuture().get();
        verify(session, times(3)).writeAsync(insertNeedsRollup1BS, CassandraProfile.collector);
    }

    @Test
    public void postRollupUsesSingleBoundStatementForOneDeleteAndUnloggedBatchesForMultipleDeletes()
            throws Exception {
        PreparedStatement insertNeedsRollupPS = mock(PreparedStatement.class);
        BoundStatement insertNeedsRollupBS = mock(BoundStatement.class, Answers.RETURNS_SELF);
        when(insertNeedsRollupPS.bind()).thenReturn(insertNeedsRollupBS);

        PreparedStatement deleteNeedsRollupPS = mock(PreparedStatement.class);
        when(deleteNeedsRollupPS.bind()).thenAnswer(
                ignored -> mock(BoundStatement.class, Answers.RETURNS_SELF));

        Session singleSession = mock(Session.class);
        when(singleSession.writeAsync(any(), any()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        UUID singleUuid = Uuids.timeBased();
        Common.postRollup("agent1", 60_000L, Set.of("Web"), Set.of(singleUuid), 300_000L,
                insertNeedsRollupPS, deleteNeedsRollupPS, 3600, singleSession,
                CassandraProfile.rollup).toCompletableFuture().get();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Statement<?>> singleCaptor = ArgumentCaptor.forClass(Statement.class);
        verify(singleSession, times(2)).writeAsync(singleCaptor.capture(), any());
        assertThat(singleCaptor.getAllValues().get(0)).isSameAs(insertNeedsRollupBS);
        assertThat(singleCaptor.getAllValues().get(1)).isInstanceOf(BoundStatement.class);

        // Multiple deletes in same partition are grouped into UNLOGGED batches and chunked
        // below MAX_SINGLE_PARTITION_BATCH_BYTES
        Session batchSession = mock(Session.class);
        when(batchSession.writeAsync(any(), any()))
                .thenAnswer(ignored -> CompletableFuture.completedFuture(null));

        Set<UUID> hundredUuids = new LinkedHashSet<>();
        for (int i = 0; i < 100; i++) {
            hundredUuids.add(Uuids.timeBased());
        }
        Common.postRollup("agent1", 60_000L, Set.of("Web"), hundredUuids, null, null,
                deleteNeedsRollupPS, -1, batchSession, CassandraProfile.rollup)
                .toCompletableFuture().get();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Statement<?>> batchCaptor = ArgumentCaptor.forClass(Statement.class);
        verify(batchSession, times(2)).writeAsync(batchCaptor.capture(), any());
        int totalDeletedRows = 0;
        for (Statement<?> stmt : batchCaptor.getAllValues()) {
            assertThat(stmt).isInstanceOf(BatchStatement.class);
            BatchStatement batch = (BatchStatement) stmt;
            assertThat(batch.getBatchType()).isEqualTo(DefaultBatchType.UNLOGGED);
            int size = Iterables.size(batch);
            assertThat(size).isGreaterThan(1);
            totalDeletedRows += size;
        }
        assertThat(totalDeletedRows).isEqualTo(100);
    }

    private static Row mockRow(long captureTime, Set<String> keys) {
        Row row = mock(Row.class);
        when(row.getInstant(0)).thenReturn(Instant.ofEpochMilli(captureTime));
        when(row.getUuid(1)).thenReturn(UUID.randomUUID());
        when(row.getSet(2, String.class)).thenReturn(keys);
        return row;
    }

    private static GaugeValue gauge(String name, long captureTime) {
        return GaugeValue.newBuilder()
                .setGaugeName(name)
                .setCaptureTime(captureTime)
                .setValue(1.0)
                .setWeight(1)
                .build();
    }
}
