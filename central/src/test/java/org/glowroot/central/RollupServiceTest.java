/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableAgentRollup;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RollupServiceTest {

    @Test
    public void test() {
        assertThat(RollupService.millisUntilNextRollup(15000)).isEqualTo(55000);
        assertThat(RollupService.millisUntilNextRollup(30000)).isEqualTo(40000);
        assertThat(RollupService.millisUntilNextRollup(45000)).isEqualTo(25000);
        assertThat(RollupService.millisUntilNextRollup(60000)).isEqualTo(10000);
    }

    @Test
    public void runInternalContinuesOtherRollupsWhenAggregateRollupFails() throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        when(aggregateDao.rollup("agent1"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("aggregate fail")));
        when(gaugeValueDao.rollup("agent1")).thenReturn(CompletableFuture.completedFuture(null));
        when(syntheticResultDao.rollup("agent1"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("agent1"), eq("agent1"), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentRollup agentRollup = ImmutableAgentRollup.builder()
                .id("agent1")
                .display("agent1")
                .lastDisplayPart("agent1")
                .build();

        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            rollupService.runInternal(List.of(agentRollup));
        } finally {
            rollupService.close();
        }

        InOrder order = inOrder(aggregateDao, gaugeValueDao, syntheticResultDao,
                centralAlertingService);
        order.verify(aggregateDao).rollup("agent1");
        order.verify(gaugeValueDao).rollup("agent1");
        order.verify(syntheticResultDao).rollup("agent1");
        order.verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("agent1"), eq("agent1"), anyLong(), eq(CassandraProfile.rollup));
        verify(centralAlertingService).checkForAllDeletedAlerts(CassandraProfile.rollup);
    }

    @Test
    public void runInternalBoundsConcurrentAgentsAndRunsStagesSequentiallyPerAgent()
            throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        AtomicInteger activeAgents = new AtomicInteger();
        AtomicInteger maxActiveAgents = new AtomicInteger();
        AtomicInteger aggregateCalls = new AtomicInteger();
        CountDownLatch firstFourStarted = new CountDownLatch(4);
        List<CompletableFuture<Void>> firstFourFutures = new ArrayList<>();
        Map<String, List<String>> stagesPerAgent = new ConcurrentHashMap<>();

        when(aggregateDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            int current = activeAgents.incrementAndGet();
            maxActiveAgents.accumulateAndGet(current, Math::max);
            stagesPerAgent.computeIfAbsent(id, k -> new ArrayList<>()).add("aggregate");
            if (aggregateCalls.incrementAndGet() <= 4) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                synchronized (firstFourFutures) {
                    firstFourFutures.add(future);
                }
                firstFourStarted.countDown();
                return future;
            }
            return CompletableFuture.completedFuture(null);
        });
        when(gaugeValueDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            stagesPerAgent.get(id).add("gauge");
            return CompletableFuture.completedFuture(null);
        });
        when(syntheticResultDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            stagesPerAgent.get(id).add("synthetic");
            return CompletableFuture.completedFuture(null);
        });
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    String id = invocation.getArgument(0);
                    stagesPerAgent.get(id).add("alert");
                    activeAgents.decrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<AgentRollup> agents = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "agent" + i;
            agents.add(ImmutableAgentRollup.builder()
                    .id(id)
                    .display(id)
                    .lastDisplayPart(id)
                    .build());
        }

        ExecutorService releaser = Executors.newSingleThreadExecutor();
        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
                try {
                    firstFourStarted.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (firstFourFutures) {
                        for (CompletableFuture<Void> f : firstFourFutures) {
                            f.complete(null);
                        }
                    }
                }
            }, releaser);

            rollupService.runInternal(agents);
            releaseFuture.get(2, TimeUnit.SECONDS);
        } finally {
            rollupService.close();
            releaser.shutdownNow();
        }

        assertThat(maxActiveAgents.get()).isEqualTo(4);
        assertThat(activeAgents.get()).isEqualTo(0);
        assertThat(stagesPerAgent).hasSize(6);
        for (List<String> stages : stagesPerAgent.values()) {
            assertThat(stages).containsExactly("aggregate", "gauge", "synthetic", "alert");
        }
        verify(centralAlertingService).checkForAllDeletedAlerts(any());
    }

    @Test
    public void rollupGaugesSkipsParentWhenChildGaugeRollupFails() throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        when(aggregateDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gaugeValueDao.rollup("parent::childA"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gaugeValueDao.rollup("parent::childB"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("child gauge fail")));
        when(syntheticResultDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentRollup childA = ImmutableAgentRollup.builder()
                .id("parent::childA")
                .display("childA")
                .lastDisplayPart("childA")
                .build();
        AgentRollup childB = ImmutableAgentRollup.builder()
                .id("parent::childB")
                .display("childB")
                .lastDisplayPart("childB")
                .build();
        AgentRollup parent = ImmutableAgentRollup.builder()
                .id("parent::")
                .display("parent")
                .lastDisplayPart("parent")
                .addChildren(childA, childB)
                .build();

        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            rollupService.runInternal(List.of(parent));
        } finally {
            rollupService.close();
        }

        verify(gaugeValueDao).rollup("parent::childA");
        verify(gaugeValueDao).rollup("parent::childB");
        verify(gaugeValueDao, never()).rollup("parent::");
        verify(aggregateDao).rollup("parent::");
        verify(syntheticResultDao).rollup("parent::");
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::"), eq("parent"), anyLong(), eq(CassandraProfile.rollup));
    }

    @Test
    public void rollupAggregatesSkipsGrandparentWhenIntermediateParentRollupFails()
            throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        when(aggregateDao.rollup("gp::parent1::child1"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(aggregateDao.rollup("gp::parent1::"))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("intermediate aggregate fail")));
        when(gaugeValueDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(syntheticResultDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentRollup child = ImmutableAgentRollup.builder()
                .id("gp::parent1::child1")
                .display("child1")
                .lastDisplayPart("child1")
                .build();
        AgentRollup parent1 = ImmutableAgentRollup.builder()
                .id("gp::parent1::")
                .display("parent1")
                .lastDisplayPart("parent1")
                .addChildren(child)
                .build();
        AgentRollup gp = ImmutableAgentRollup.builder()
                .id("gp::")
                .display("gp")
                .lastDisplayPart("gp")
                .addChildren(parent1)
                .build();

        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            rollupService.runInternal(List.of(gp));
        } finally {
            rollupService.close();
        }

        verify(aggregateDao).rollup("gp::parent1::child1");
        verify(aggregateDao).rollup("gp::parent1::");
        verify(aggregateDao, never()).rollup("gp::");
        verify(gaugeValueDao).rollup("gp::");
        verify(syntheticResultDao).rollup("gp::");
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("gp::"), eq("gp"), anyLong(), eq(CassandraProfile.rollup));
    }

    @Test
    public void rollupGaugesRollsUpRemainingHealthySiblingsWhenFourChildrenFailAsynchronously()
            throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        AtomicInteger childCalls = new AtomicInteger();
        CountDownLatch firstFourStarted = new CountDownLatch(4);
        List<CompletableFuture<Void>> firstFourFutures = new ArrayList<>();
        Set<String> attemptedChildren = ConcurrentHashMap.newKeySet();
        Set<String> healthyChildren = ConcurrentHashMap.newKeySet();

        when(aggregateDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gaugeValueDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if ("parent::".equals(id)) {
                return CompletableFuture.completedFuture(null);
            }
            attemptedChildren.add(id);
            if (childCalls.incrementAndGet() <= 4) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                synchronized (firstFourFutures) {
                    firstFourFutures.add(future);
                }
                firstFourStarted.countDown();
                return future;
            }
            healthyChildren.add(id);
            return CompletableFuture.completedFuture(null);
        });
        when(syntheticResultDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<AgentRollup> children = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "parent::child" + i;
            children.add(ImmutableAgentRollup.builder()
                    .id(id)
                    .display("child" + i)
                    .lastDisplayPart("child" + i)
                    .build());
        }
        AgentRollup parent = ImmutableAgentRollup.builder()
                .id("parent::")
                .display("parent")
                .lastDisplayPart("parent")
                .addAllChildren(children)
                .build();

        ExecutorService releaser = Executors.newSingleThreadExecutor();
        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
                try {
                    firstFourStarted.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (firstFourFutures) {
                        for (CompletableFuture<Void> f : firstFourFutures) {
                            f.completeExceptionally(new RuntimeException("child gauge fail"));
                        }
                    }
                }
            }, releaser);

            rollupService.runInternal(List.of(parent));
            releaseFuture.get(2, TimeUnit.SECONDS);
        } finally {
            rollupService.close();
            releaser.shutdownNow();
        }

        assertThat(attemptedChildren).hasSize(6);
        assertThat(healthyChildren).hasSize(2);
        verify(gaugeValueDao, never()).rollup("parent::");
        verify(aggregateDao).rollup("parent::");
        verify(syntheticResultDao).rollup("parent::");
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::"), eq("parent"), anyLong(), eq(CassandraProfile.rollup));
        verify(centralAlertingService).checkForAllDeletedAlerts(CassandraProfile.rollup);
    }

    @Test
    public void rollupAggregatesRollsUpRemainingHealthySiblingsWhenFourChildrenFailAsynchronously()
            throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        AtomicInteger childCalls = new AtomicInteger();
        CountDownLatch firstFourStarted = new CountDownLatch(4);
        List<CompletableFuture<Void>> firstFourFutures = new ArrayList<>();
        Set<String> attemptedChildren = ConcurrentHashMap.newKeySet();
        Set<String> healthyChildren = ConcurrentHashMap.newKeySet();

        when(aggregateDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if ("parent::".equals(id)) {
                return CompletableFuture.completedFuture(null);
            }
            attemptedChildren.add(id);
            if (childCalls.incrementAndGet() <= 4) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                synchronized (firstFourFutures) {
                    firstFourFutures.add(future);
                }
                firstFourStarted.countDown();
                return future;
            }
            healthyChildren.add(id);
            return CompletableFuture.completedFuture(null);
        });
        when(gaugeValueDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(syntheticResultDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        List<AgentRollup> children = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "parent::child" + i;
            children.add(ImmutableAgentRollup.builder()
                    .id(id)
                    .display("child" + i)
                    .lastDisplayPart("child" + i)
                    .build());
        }
        AgentRollup parent = ImmutableAgentRollup.builder()
                .id("parent::")
                .display("parent")
                .lastDisplayPart("parent")
                .addAllChildren(children)
                .build();

        ExecutorService releaser = Executors.newSingleThreadExecutor();
        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> {
                try {
                    firstFourStarted.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (firstFourFutures) {
                        for (CompletableFuture<Void> f : firstFourFutures) {
                            f.completeExceptionally(new RuntimeException("child aggregate fail"));
                        }
                    }
                }
            }, releaser);

            rollupService.runInternal(List.of(parent));
            releaseFuture.get(2, TimeUnit.SECONDS);
        } finally {
            rollupService.close();
            releaser.shutdownNow();
        }

        assertThat(attemptedChildren).hasSize(6);
        assertThat(healthyChildren).hasSize(2);
        verify(aggregateDao, never()).rollup("parent::");
        verify(gaugeValueDao).rollup("parent::");
        verify(syntheticResultDao).rollup("parent::");
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::"), eq("parent"), anyLong(), eq(CassandraProfile.rollup));
        verify(centralAlertingService).checkForAllDeletedAlerts(CassandraProfile.rollup);
    }

    @Test
    public void rollupSyntheticMonitorsAndAlertsContinueToParentWhenChildFails() throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        when(aggregateDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gaugeValueDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(syntheticResultDao.rollup("parent::child1"))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("child synthetic fail")));
        when(syntheticResultDao.rollup("parent::"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::child1"), eq("child1"), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("child alert fail")));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::"), eq("parent"), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentRollup child1 = ImmutableAgentRollup.builder()
                .id("parent::child1")
                .display("child1")
                .lastDisplayPart("child1")
                .build();
        AgentRollup parent = ImmutableAgentRollup.builder()
                .id("parent::")
                .display("parent")
                .lastDisplayPart("parent")
                .addChildren(child1)
                .build();

        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            rollupService.runInternal(List.of(parent));
        } finally {
            rollupService.close();
        }

        verify(syntheticResultDao).rollup("parent::child1");
        verify(syntheticResultDao).rollup("parent::");
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::child1"), eq("child1"), anyLong(), eq(CassandraProfile.rollup));
        verify(centralAlertingService).checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                eq("parent::"), eq("parent"), anyLong(), eq(CassandraProfile.rollup));
        verify(centralAlertingService).checkForAllDeletedAlerts(CassandraProfile.rollup);
    }

    @Test
    public void runInternalCapsGlobalNodeConcurrencyAcrossMultiLevelRollupTrees()
            throws Exception {
        ActiveAgentDao activeAgentDao = mock(ActiveAgentDao.class);
        AggregateDao aggregateDao = mock(AggregateDao.class);
        GaugeValueDao gaugeValueDao = mock(GaugeValueDao.class);
        SyntheticResultDao syntheticResultDao = mock(SyntheticResultDao.class);
        CentralAlertingService centralAlertingService = mock(CentralAlertingService.class);
        Clock clock = fixedClock();

        AtomicInteger activeRollups = new AtomicInteger();
        AtomicInteger maxActiveRollups = new AtomicInteger();
        Set<String> completedAggregates = ConcurrentHashMap.newKeySet();

        ExecutorService asyncPool = Executors.newFixedThreadPool(8);
        when(aggregateDao.rollup(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            int current = activeRollups.incrementAndGet();
            maxActiveRollups.accumulateAndGet(current, Math::max);
            CompletableFuture<Void> future = new CompletableFuture<>();
            asyncPool.execute(() -> {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completedAggregates.add(id);
                    activeRollups.decrementAndGet();
                    future.complete(null);
                }
            });
            return future;
        });
        when(gaugeValueDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(syntheticResultDao.rollup(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                anyString(), anyString(), anyLong(), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 4 top-level parents x 4 children each = 16 leaf nodes + 4 parents (20 total nodes)
        List<AgentRollup> parents = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            String parentId = "parent" + p + "::";
            List<AgentRollup> children = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                String childId = parentId + "child" + c;
                children.add(ImmutableAgentRollup.builder()
                        .id(childId)
                        .display("child" + c)
                        .lastDisplayPart("child" + c)
                        .build());
            }
            parents.add(ImmutableAgentRollup.builder()
                    .id(parentId)
                    .display("parent" + p)
                    .lastDisplayPart("parent" + p)
                    .addAllChildren(children)
                    .build());
        }

        RollupService rollupService = new RollupService(activeAgentDao, aggregateDao, gaugeValueDao,
                syntheticResultDao, centralAlertingService, clock);
        try {
            rollupService.runInternal(parents);
        } finally {
            rollupService.close();
            asyncPool.shutdownNow();
        }

        assertThat(maxActiveRollups.get()).isLessThanOrEqualTo(4);
        assertThat(activeRollups.get()).isEqualTo(0);
        assertThat(completedAggregates).hasSize(20);
        verify(centralAlertingService).checkForAllDeletedAlerts(CassandraProfile.rollup);
    }

    private static Clock fixedClock() {
        return new Clock() {
            @Override
            public long currentTimeMillis() {
                return 10000L;
            }
        };
    }
}
