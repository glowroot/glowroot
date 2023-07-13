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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.spotify.futures.CompletableFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class RollupService implements Runnable {

    private static final int MIN_WORKER_THREADS = 1;
    private static final int MAX_WORKER_THREADS = 4;
    private static final int INITIAL_WORKER_THREADS = 2;

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
        Session.setInRollupThread(true);
        int counter = 0;
        int numWorkerThreads = INITIAL_WORKER_THREADS;
        ExecutorService workerExecutor = newWorkerExecutor(numWorkerThreads);
        while (!closed) {
            try {
                MILLISECONDS.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                // perform larger sweep approx every 100 minutes
                long lastXMillis = counter++ % 100 == 0 ? DAYS.toMillis(7) : MINUTES.toMillis(30);
                Stopwatch stopwatch = Stopwatch.createStarted();
                List<AgentRollup> agentRollups =
                        activeAgentDao.readRecentlyActiveAgentRollups(lastXMillis);
                runInternal(agentRollups, workerExecutor);
                long elapsedInSeconds = stopwatch.elapsed(SECONDS);
                int oldNumWorkerThreads = numWorkerThreads;
                if (elapsedInSeconds > 300) {
                    if (numWorkerThreads < MAX_WORKER_THREADS) {
                        numWorkerThreads++;
                    } else {
                        logger.warn("rolling up data across {} agent rollup took {} seconds (using"
                                + " {} threads)", count(agentRollups), elapsedInSeconds,
                                numWorkerThreads);
                    }
                } else if (elapsedInSeconds < 60 && numWorkerThreads > MIN_WORKER_THREADS) {
                    numWorkerThreads--;
                }
                if (numWorkerThreads != oldNumWorkerThreads) {
                    ExecutorService oldWorkerExecutor = workerExecutor;
                    workerExecutor = newWorkerExecutor(numWorkerThreads);
                    oldWorkerExecutor.shutdown();
                    if (!oldWorkerExecutor.awaitTermination(10, SECONDS)) {
                        logger.error("timed out waiting for old worker rollup thread to terminate");
                    }
                }
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method below)
                logger.debug(e.getMessage(), e);
                continue;
            } catch (Throwable t) {
                // this probably should never happen since runInternal catches and logs exceptions
                logger.error(t.getMessage(), t);
            }
        }
        // shutdownNow() is needed here to send interrupt to worker rollup thread
        workerExecutor.shutdownNow();
        try {
            if (!workerExecutor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for worker rollup thread to terminate");
            }
        } catch (InterruptedException e) {
            // this is unexpected (but not harmful since already closing)
            logger.error(e.getMessage(), e);
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
    private void runInternal(List<AgentRollup> agentRollups, ExecutorService workerExecutor) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup agentRollup : shuffle(agentRollups)) {
            futures.addAll(rollupAggregates(agentRollup, workerExecutor));
            futures.add(rollupGauges(agentRollup, workerExecutor));
            futures.addAll(rollupSyntheticMonitors(agentRollup, workerExecutor));
            // checking aggregate and gauge alerts after rollup since their calculation can depend
            // on rollups depending on time period length (and alerts on rollups are not checked
            // anywhere else)
            //
            // agent (not rollup) alerts are also checked right after receiving the respective data
            // (aggregate/gauge/heartbeat) from the agent, but need to also check these once a
            // minute in case no data has been received from the agent recently
            futures.addAll(
                    checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup, workerExecutor));
        }
        // none of the futures should fail since they all catch and log exception at the end
        MoreFutures.waitForAll(futures);
        try {
            // FIXME keep this here as fallback, but also resolve alerts immediately when they are
            // deleted (or when their condition is updated)
            centralAlertingService.checkForAllDeletedAlerts();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private List<CompletableFuture<?>> rollupAggregates(AgentRollup agentRollup,
            ExecutorService workerExecutor) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            futures.addAll(rollupAggregates(childAgentRollup, workerExecutor));
        }
        futures.add(CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    aggregateDao.rollup(agentRollup.id());
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                } catch (Throwable t) {
                    logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                }
            }
        }, workerExecutor));
        return futures;
    }

    private CompletableFuture<?> rollupGauges(AgentRollup agentRollup,
            ExecutorService workerExecutor) {
        List<AgentRollup> childAgentRollups = agentRollup.children();
        if (childAgentRollups.isEmpty()) {
            // optimization of common case
            return CompletableFuture.runAsync(new RollupGauges(agentRollup.id()), workerExecutor);
        }
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : shuffle(childAgentRollups)) {
            futures.add(rollupGauges(childAgentRollup, workerExecutor));
        }
        // using _allAsList_ because need to _not_ roll up parent if exception occurs while
        // rolling up a child, since gauge values initial roll up from children is done on the 1-min
        // aggregates of the children
        return CompletableFutures.allAsList(futures)
                .thenRunAsync(new RollupGauges(agentRollup.id()), workerExecutor);
    }

    private List<CompletableFuture<?>> rollupSyntheticMonitors(AgentRollup agentRollup,
            ExecutorService workerExecutor) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            futures.addAll(rollupSyntheticMonitors(childAgentRollup, workerExecutor));
        }
        futures.add(CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    syntheticResultDao.rollup(agentRollup.id());
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                } catch (Throwable t) {
                    logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                }
            }
        }, workerExecutor));
        return futures;
    }

    private List<CompletableFuture<?>> checkAggregateAndGaugeAndHeartbeatAlertsAsync(AgentRollup agentRollup,
                                                                                     ExecutorService workerExecutor) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            futures.addAll(checkAggregateAndGaugeAndHeartbeatAlertsAsync(childAgentRollup,
                    workerExecutor));
        }
        futures.add(CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(
                            agentRollup.id(), agentRollup.display(), clock.currentTimeMillis());
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                } catch (Throwable t) {
                    logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                }
            }
        }, workerExecutor));
        return futures;
    }

    private static ExecutorService newWorkerExecutor(int numWorkerThreads) {
        return MoreExecutors2.newFixedThreadPool(numWorkerThreads, "Rollup-Worker-%d");
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
    interface AgentRollupConsumer {
        void accept(AgentRollup agentRollup) throws Exception;
    }

    private class RollupGauges implements Runnable {

        private final String agentRollupId;

        private RollupGauges(String agentRollupId) {
            this.agentRollupId = agentRollupId;
        }

        @Override
        public void run() {
            try {
                gaugeValueDao.rollup(agentRollupId);
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollupId, t.getMessage(), t);
            }
        }
    }
}
