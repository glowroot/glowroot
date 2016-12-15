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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;

import static java.util.concurrent.TimeUnit.SECONDS;

public class RollupService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    private final AgentDao agentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepository configRepository;
    private final AlertingService alertingService;
    private final DownstreamServiceImpl downstreamService;
    private final Clock clock;

    private final ExecutorService executor;

    private volatile boolean stopped;

    public RollupService(AgentDao agentDao, AggregateDao aggregateDao, GaugeValueDao gaugeValueDao,
            ConfigRepository configRepository, AlertingService alertingService,
            DownstreamServiceImpl downstreamService, Clock clock) {
        this.agentDao = agentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.configRepository = configRepository;
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
                runInternal();
            } catch (InterruptedException e) {
                if (stopped) {
                    return;
                }
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer rollup loop", traceHeadline = "Outer rollup loop",
            timer = "outer rollup loop")
    private void runInternal() throws InterruptedException {
        Glowroot.setTransactionOuter();
        for (AgentRollup agentRollup : agentDao.readAgentRollups()) {
            rollupAggregates(agentRollup, null);
            rollupGauges(agentRollup, null);
            checkTransactionAlerts(agentRollup);
            checkGaugeAlerts(agentRollup);
            updateAgentConfigIfConnectedAndNeeded(agentRollup);
        }
    }

    private void rollupAggregates(AgentRollup agentRollup, @Nullable String parentAgentRollupId)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            rollupAggregates(childAgentRollup, agentRollup.id());
        }
        try {
            aggregateDao.rollup(agentRollup.id(), parentAgentRollupId,
                    agentRollup.children().isEmpty());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    // returns true on success, false on failure
    private boolean rollupGauges(AgentRollup agentRollup, @Nullable String parentAgentRollupId)
            throws InterruptedException {
        // important to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        boolean success = true;
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            boolean childSuccess = rollupGauges(childAgentRollup, agentRollup.id());
            success = success && childSuccess;
        }
        if (!success) {
            // also important to not roll up parent if exception occurs while rolling up a child,
            // since gauge values initial roll up from children is done on the 1-min aggregates of
            // the children
            return false;
        }
        try {
            gaugeValueDao.rollup(agentRollup.id(), parentAgentRollupId,
                    agentRollup.children().isEmpty());
            return true;
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
            return false;
        }
    }

    private void checkTransactionAlerts(AgentRollup agentRollup) throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkTransactionAlerts(childAgentRollup);
        }
        try {
            checkTransactionAlerts(agentRollup.id(), clock.currentTimeMillis());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    private void checkGaugeAlerts(AgentRollup agentRollup) throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            checkGaugeAlerts(childAgentRollup);
        }
        try {
            checkGaugeAlerts(agentRollup.id(), clock.currentTimeMillis());
        } catch (InterruptedException e) {
            // shutdown requested
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
        }
    }

    private void updateAgentConfigIfConnectedAndNeeded(AgentRollup agentRollup)
            throws InterruptedException {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            updateAgentConfigIfConnectedAndNeeded(childAgentRollup);
        }
        if (agentRollup.children().isEmpty()) {
            try {
                downstreamService.updateAgentConfigIfConnectedAndNeeded(agentRollup.id());
            } catch (InterruptedException e) {
                // shutdown requested
                throw e;
            } catch (Exception e) {
                logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
            }
        }
    }

    private void checkTransactionAlerts(String agentId, long captureTime)
            throws InterruptedException {
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
        if (smtpConfig.host().isEmpty()) {
            return;
        }
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getTransactionAlertConfigs(agentId);
        } catch (IOException e) {
            logger.error("{} - {}", agentId, e.getMessage(), e);
            return;
        }
        if (alertConfigs.isEmpty()) {
            return;
        }
        for (AlertConfig alertConfig : alertConfigs) {
            try {
                checkTransactionAlert(agentId, alertConfig, captureTime, smtpConfig);
            } catch (InterruptedException e) {
                // shutdown requested
                throw e;
            } catch (Exception e) {
                logger.error("{} - {}", agentId, e.getMessage(), e);
            }
        }
    }

    private void checkGaugeAlerts(String agentId, long captureTime) throws InterruptedException {
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
        if (smtpConfig.host().isEmpty()) {
            return;
        }
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getGaugeAlertConfigs(agentId);
        } catch (IOException e) {
            logger.error("{} - {}", agentId, e.getMessage(), e);
            return;
        }
        if (alertConfigs.isEmpty()) {
            return;
        }
        for (AlertConfig alertConfig : alertConfigs) {
            try {
                checkGaugeAlert(agentId, alertConfig, captureTime, smtpConfig);
            } catch (InterruptedException e) {
                // shutdown requested
                throw e;
            } catch (Exception e) {
                logger.error("{} - {}", agentId, e.getMessage(), e);
            }
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check transaction alert",
            traceHeadline = "Check transaction alert: {{0}}", timer = "check transaction alert")
    private void checkTransactionAlert(String agentId, AlertConfig alertConfig,
            long captureTime, SmtpConfig smtpConfig) throws Exception {
        alertingService.checkTransactionAlert(agentId, alertConfig, captureTime, smtpConfig);
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check gauge alert",
            traceHeadline = "Check gauge alert: {{0}}", timer = "check gauge alert")
    private void checkGaugeAlert(String agentId, AlertConfig alertConfig, long captureTime,
            SmtpConfig smtpConfig) throws Exception {
        alertingService.checkGaugeAlert(agentId, alertConfig, captureTime, smtpConfig);
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
