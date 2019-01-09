/*
 * Copyright 2016-2019 the original author or authors.
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final ActiveAgentDao activeAgentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;

    private final ExecutorService executor;

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
        executor = Executors.newSingleThreadExecutor();
        executor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        Session.setInRollupThread(true);
        int counter = 0;
        while (!closed) {
            try {
                MILLISECONDS.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                // perform larger sweep approx every 100 minutes
                long lastXMillis = counter++ % 100 == 0 ? DAYS.toMillis(7) : MINUTES.toMillis(30);
                Stopwatch stopwatch = Stopwatch.createStarted();
                List<AgentRollup> agentRollups =
                        activeAgentDao.readRecentlyActiveAgentRollups(lastXMillis);
                runInternal(agentRollups);
                long elapsedInSeconds = stopwatch.elapsed(SECONDS);
                if (elapsedInSeconds > 300) {
                    logger.warn("rolling up data across {} agent rollup took {} seconds",
                            count(agentRollups), elapsedInSeconds);
                }
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method below)
                logger.debug(e.getMessage(), e);
                continue;
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to RollupService thread
        executor.shutdownNow();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for rollup thread to terminate");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer rollup loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal(List<AgentRollup> agentRollups) throws Exception {
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup agentRollup : shuffle(agentRollups)) {
            rollupAggregates(agentRollup);
            rollupGauges(agentRollup);
            rollupSyntheticMonitors(agentRollup);
            // checking aggregate and gauge alerts after rollup since their calculation can depend
            // on rollups depending on time period length (and alerts on rollups are not checked
            // anywhere else)
            //
            // agent (not rollup) alerts are also checked right after receiving the respective data
            // (aggregate/gauge/heartbeat) from the agent, but need to also check these once a
            // minute in case no data has been received from the agent recently
            checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup);
        }
        // FIXME keep this here as fallback, but also resolve alerts immediately when they are
        // deleted (or when their condition is updated)
        centralAlertingService.checkForAllDeletedAlerts();
    }

    private void rollupAggregates(AgentRollup agentRollup) throws InterruptedException {
        // randomize order so that multiple central collector nodes will be less likely to perform
        // duplicative work
        for (AgentRollup childAgentRollup : shuffle(agentRollup.children())) {
            rollupAggregates(childAgentRollup);
        }
        try {
            aggregateDao.rollup(agentRollup.id());
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    // returns true on success, false on failure
    private boolean rollupGauges(AgentRollup agentRollup) throws InterruptedException {
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        boolean success = true;
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            boolean childSuccess = rollupGauges(childAgentRollup);
            success = success && childSuccess;
        }
        if (!success) {
            // need to _not_ roll up parent if exception occurs while rolling up a child, since
            // gauge values initial roll up from children is done on the 1-min aggregates of the
            // children
            return false;
        }
        try {
            gaugeValueDao.rollup(agentRollup.id());
            return true;
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
            return false;
        }
    }

    private void rollupSyntheticMonitors(AgentRollup agentRollup) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupSyntheticMonitors(childAgentRollup);
        }
        try {
            syntheticResultDao.rollup(agentRollup.id());
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    private void checkAggregateAndGaugeAndHeartbeatAlertsAsync(AgentRollup agentRollup)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkAggregateAndGaugeAndHeartbeatAlertsAsync(childAgentRollup);
        }
        centralAlertingService.checkAggregateAndGaugeAndHeartbeatAlertsAsync(agentRollup.id(),
                agentRollup.display(), clock.currentTimeMillis());
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
}
