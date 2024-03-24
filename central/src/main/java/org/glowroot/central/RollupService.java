/*
 * Copyright 2016-2023 the original author or authors.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.spotify.futures.CompletableFutures;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.glowroot.common2.repo.CassandraProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.TimeUnit.*;

class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final ActiveAgentDao activeAgentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;

    private final ExecutorService mainLoopExecutor;

    private volatile boolean closed;

    RollupService(ActiveAgentDao activeAgentDao, AggregateDao aggregateDao,
                  GaugeValueDao gaugeValueDao, SyntheticResultDao syntheticResultDao,
                  CentralAlertingService centralAlertingService, Clock clock) {
        this.activeAgentDao = activeAgentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.syntheticResultDao = syntheticResultDao;
        this.centralAlertingService = centralAlertingService;
        this.clock = clock;
        mainLoopExecutor = MoreExecutors2.newSingleThreadExecutor("Rollup-Main-Loop");
        mainLoopExecutor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        int counter = 0;
        while (!closed) {
            try {
                MILLISECONDS.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                // perform larger sweep approx every 100 minutes
                long lastXMillis = counter++ % 100 == 0 ? DAYS.toMillis(7) : MINUTES.toMillis(30);
                Stopwatch stopwatch = Stopwatch.createStarted();
                List<AgentRollup> agentRollups =
                        activeAgentDao.readRecentlyActiveAgentRollups(lastXMillis, CassandraProfile.rollup).toCompletableFuture().get();
                runInternal(agentRollups);
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method below)
                logger.debug(e.getMessage(), e);
                continue;
            } catch (Throwable t) {
                // this probably should never happen since runInternal catches and logs exceptions
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to main rollup thread
        mainLoopExecutor.shutdownNow();
        if (!mainLoopExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for main rollup thread to terminate");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer rollup loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal(List<AgentRollup> agentRollups) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup agentRollup : shuffle(agentRollups)) {
            CompletableFuture<?> fut = rollupAggregates(agentRollup)
                    .thenCompose(ignore -> {
                        return rollupGauges(agentRollup);
                    }).thenCompose(ignore -> {
                        return rollupSyntheticMonitors(agentRollup);
                    }).thenCompose(ignore -> {
                        // checking aggregate and gauge alerts after rollup since their calculation can depend
                        // on rollups depending on time period length (and alerts on rollups are not checked
                        // anywhere else)
                        //
                        // agent (not rollup) alerts are also checked right after receiving the respective data
                        // (aggregate/gauge/heartbeat) from the agent, but need to also check these once a
                        // minute in case no data has been received from the agent recently
                        return checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup);
                    }).toCompletableFuture();
            futures.add(fut);
        }
        // none of the futures should fail since they all catch and log exception at the end
        CompletableFutures.allAsList(futures).thenCompose(ignore -> {
            return centralAlertingService.checkForAllDeletedAlerts(CassandraProfile.rollup);
        }).exceptionally(t -> {
            logger.error(t.getMessage(), t);
            return null;
        }).toCompletableFuture().join();
    }

    private CompletionStage<?> rollupAggregates(AgentRollup agentRollup) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            futures.add(rollupAggregates(childAgentRollup).toCompletableFuture());
        }
        return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
            try {
                return aggregateDao.rollup(agentRollup.id());
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletionStage<?> rollupGauges(AgentRollup agentRollup) {
        List<AgentRollup> childAgentRollups = agentRollup.children();
        if (childAgentRollups.isEmpty()) {
            // optimization of common case
            try {
                return gaugeValueDao.rollup(agentRollup.id());
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                return CompletableFuture.completedFuture(null);
            }
        }
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        List<CompletionStage<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : shuffle(childAgentRollups)) {
            futures.add(rollupGauges(childAgentRollup));
        }
        // using _allAsList_ because need to _not_ roll up parent if exception occurs while
        // rolling up a child, since gauge values initial roll up from children is done on the 1-min
        // aggregates of the children
        return CompletableFutures.allAsList(futures)
                .thenCompose(ignored -> {
                    try {
                        return gaugeValueDao.rollup(agentRollup.id());
                    } catch (InterruptedException e) {
                        // probably shutdown requested (see close method above)
                    } catch (Throwable t) {
                        logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletionStage<?> rollupSyntheticMonitors(AgentRollup agentRollup) {
        List<CompletionStage<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            futures.add(rollupSyntheticMonitors(childAgentRollup));
        }
        return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
            try {
                return syntheticResultDao.rollup(agentRollup.id());
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletionStage<?> checkAggregateAndGaugeAndHeartbeatAlertsAsync(AgentRollup agentRollup) {
        List<CompletionStage<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            futures.add(checkAggregateAndGaugeAndHeartbeatAlertsAsync(childAgentRollup));
        }
        return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
            try {
                return centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                        agentRollup.id(), agentRollup.display(), clock.currentTimeMillis(), CassandraProfile.rollup);
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private static <T> List<T> shuffle(List<T> agentRollups) {
        List<T> mutable = new ArrayList<>(agentRollups);
        Collections.shuffle(mutable);
        return mutable;
    }

    private static int count(List<AgentRollup> agentRollups) {
        int count = agentRollups.size();
        for (AgentRollup agentRollup : agentRollups) {
            count += count(agentRollup.children());
        }
        return count;
    }

    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }

    @FunctionalInterface
    interface AgentRollupComposer {
        CompletionStage<?> accept(AgentRollup agentRollup);
    }
}
