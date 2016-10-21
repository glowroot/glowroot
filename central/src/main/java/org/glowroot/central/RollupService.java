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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.datastax.driver.core.exceptions.ReadTimeoutException;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.storage.AgentDao;
import org.glowroot.central.storage.AggregateDao;
import org.glowroot.central.storage.GaugeValueDao;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

public class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final AgentDao agentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final AlertingService alertingService;
    private final DownstreamServiceImpl downstreamService;
    private final Clock clock;

    private final ExecutorService executor;

    private volatile boolean stopped;

    public RollupService(AgentDao agentDao, AggregateDao aggregateDao, GaugeValueDao gaugeValueDao,
            AlertingService alertingService, DownstreamServiceImpl downstreamService, Clock clock) {
        this.agentDao = agentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.alertingService = alertingService;
        this.downstreamService = downstreamService;
        this.clock = clock;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(castInitialized(this));
    }

    public void close() throws InterruptedException {
        stopped = true;
        // shutdownNow() is needed here to send interrupt to RollupService thread
        executor.shutdownNow();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(millisUntilNextRollup(clock.currentTimeMillis()));
                for (AgentRollup agentRollup : agentDao.readAgentRollups()) {
                    rollupAggregates(agentRollup, null);
                    rollupGauges(agentRollup, null);
                    checkTransactionAlerts(agentRollup);
                    checkGaugeAlerts(agentRollup);
                    updateAgentConfigIfConnectedAndNeeded(agentRollup);
                }
            } catch (InterruptedException e) {
                if (stopped) {
                    return;
                }
            }
        }
    }

    private void rollupAggregates(AgentRollup agentRollup, @Nullable String parentAgentRollup)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupAggregates(childAgentRollup, agentRollup.name());
        }
        try {
            aggregateDao.rollup(agentRollup.name(), parentAgentRollup,
                    agentRollup.children().isEmpty());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    // returns true on success, false on failure
    private boolean rollupGauges(AgentRollup agentRollup, @Nullable String parentAgentRollup)
            throws InterruptedException {
        // important to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        boolean success = true;
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            boolean childSuccess = rollupGauges(childAgentRollup, agentRollup.name());
            success = success && childSuccess;
        }
        if (!success) {
            // also important to not roll up parent if exception occurs while rolling up a child,
            // since gauge values initial roll up from children is done on the 1-min aggregates of
            // the children
            return false;
        }
        try {
            gaugeValueDao.rollup(agentRollup.name(), parentAgentRollup,
                    agentRollup.children().isEmpty());
            return true;
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    private void checkGaugeAlerts(AgentRollup agentRollup) throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkGaugeAlerts(childAgentRollup);
        }
        try {
            alertingService.checkGaugeAlerts(agentRollup.name(), clock.currentTimeMillis(),
                    ReadTimeoutException.class);
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void checkTransactionAlerts(AgentRollup agentRollup) throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkTransactionAlerts(childAgentRollup);
        }
        try {
            alertingService.checkTransactionAlerts(agentRollup.name(),
                    clock.currentTimeMillis(), ReadTimeoutException.class);
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void updateAgentConfigIfConnectedAndNeeded(AgentRollup agentRollup)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            updateAgentConfigIfConnectedAndNeeded(childAgentRollup);
        }
        if (agentRollup.children().isEmpty()) {
            try {
                downstreamService.updateAgentConfigIfConnectedAndNeeded(agentRollup.name());
            } catch (InterruptedException e) {
                // shutdown requested
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @VisibleForTesting
    static long millisUntilNextRollup(long currentTimeMillis) {
        return 60000 - (currentTimeMillis - 10000) % 60000;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}
