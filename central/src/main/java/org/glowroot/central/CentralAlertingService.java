/*
 * Copyright 2017 the original author or authors.
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;

import static java.util.concurrent.TimeUnit.MINUTES;

public class CentralAlertingService {

    private static final Logger logger = LoggerFactory.getLogger(CentralAlertingService.class);

    private final ConfigRepositoryImpl configRepository;
    private final AlertingService alertingService;

    private final ExecutorService alertCheckingExecutor;

    private final Stopwatch stopwatch = Stopwatch.createStarted();

    CentralAlertingService(ConfigRepositoryImpl configRepository, AlertingService alertingService) {
        this.configRepository = configRepository;
        this.alertingService = alertingService;
        alertCheckingExecutor = Executors.newSingleThreadExecutor();
    }

    void close() {
        // then shutdown alert checking executor
        alertCheckingExecutor.shutdown();
    }

    void checkForDeletedAlerts(String agentRollupId) throws Exception {
        alertingService.checkForDeletedAlerts(agentRollupId);
    }

    void checkAggregateAlertsAsync(String agentId, String agentDisplay, long endTime) {
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getAlertConfigs(agentId);
        } catch (Exception e) {
            logger.error("{} - {}", agentDisplay, e.getMessage(), e);
            return;
        }
        List<AlertConfig> aggregateAlertConfigs = Lists.newArrayList();
        for (AlertConfig alertConfig : alertConfigs) {
            AlertCondition condition = alertConfig.getCondition();
            if (isAggregateMetricCondition(condition)) {
                aggregateAlertConfigs.add(alertConfig);
            }
        }
        checkAlertsAsync(agentId, agentDisplay, endTime, aggregateAlertConfigs);
    }

    void checkGaugeAndHeartbeatAlertsAsync(String agentId, String agentDisplay, long endTime) {
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getAlertConfigs(agentId);
        } catch (Exception e) {
            logger.error("{} - {}", agentDisplay, e.getMessage(), e);
            return;
        }
        List<AlertConfig> gaugeAndHeartbeatAlertConfigs = Lists.newArrayList();
        for (AlertConfig alertConfig : alertConfigs) {
            AlertCondition condition = alertConfig.getCondition();
            if (isGaugeMetricCondition(condition)
                    || condition.getValCase() == AlertCondition.ValCase.HEARTBEAT_CONDITION) {
                gaugeAndHeartbeatAlertConfigs.add(alertConfig);
            }
        }
        checkAlertsAsync(agentId, agentDisplay, endTime, gaugeAndHeartbeatAlertConfigs);
    }

    void checkAggregateAndGaugeAndHeartbeatAlertsAsync(String agentId, String agentDisplay,
            long endTime) {
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getAlertConfigs(agentId);
        } catch (Exception e) {
            logger.error("{} - {}", agentDisplay, e.getMessage(), e);
            return;
        }
        List<AlertConfig> aggregateAndGaugeAndHeartbeatAlertConfigs = Lists.newArrayList();
        for (AlertConfig alertConfig : alertConfigs) {
            AlertCondition condition = alertConfig.getCondition();
            if (condition.getValCase() == AlertCondition.ValCase.METRIC_CONDITION
                    || condition.getValCase() == AlertCondition.ValCase.HEARTBEAT_CONDITION) {
                aggregateAndGaugeAndHeartbeatAlertConfigs.add(alertConfig);
            }
        }
        checkAlertsAsync(agentId, agentDisplay, endTime, aggregateAndGaugeAndHeartbeatAlertConfigs);
    }

    void checkAlertsAsync(String agentId, String agentDisplay, long endTime,
            List<AlertConfig> alertConfigs) {
        alertCheckingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runInternal();
                } catch (Throwable t) {
                    logger.error("{} - {}", agentDisplay, t.getMessage(), t);
                }
            }
            private void runInternal() throws InterruptedException {
                for (AlertConfig alertConfig : alertConfigs) {
                    try {
                        checkAlert(agentId, agentDisplay, endTime, alertConfig);
                    } catch (InterruptedException e) {
                        // shutdown requested
                        throw e;
                    } catch (Exception e) {
                        logger.error("{} - {}", agentDisplay, e.getMessage(), e);
                    }
                }
            }
        });
    }

    private void checkAlert(String agentId, String agentDisplay, long endTime,
            AlertConfig alertConfig) throws Exception {
        AlertCondition alertCondition = alertConfig.getCondition();
        switch (alertCondition.getValCase()) {
            case METRIC_CONDITION:
                checkMetricAlert(agentId, agentDisplay, alertCondition,
                        alertCondition.getMetricCondition(), alertConfig.getNotification(),
                        endTime);
                break;
            case HEARTBEAT_CONDITION:
                if (stopwatch.elapsed(MINUTES) >= 4) {
                    // give agents plenty of time to re-connect after central start-up, needs to be
                    // at least enough time for grpc max reconnect backoff which is 2 minutes
                    // +/- 20% jitter (see io.grpc.internal.ExponentialBackoffPolicy) but better to
                    // give a bit extra (4 minutes above) to avoid false heartbeat alert
                    checkHeartbeatAlert(agentId, agentDisplay, alertCondition,
                            alertCondition.getHeartbeatCondition(), alertConfig.getNotification());
                }
                break;
            default:
                throw new IllegalStateException(
                        "Unexpected alert condition: " + alertCondition.getValCase().name());
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check metric alert", traceHeadline = "Check metric alert: {{0}}",
            timer = "check metric alert")
    private void checkMetricAlert(String agentId, String agentDisplay,
            AlertCondition alertCondition, MetricCondition metricCondition,
            AlertNotification alertNotification, long endTime) throws Exception {
        alertingService.checkMetricAlert(agentId, agentDisplay, alertCondition, metricCondition,
                alertNotification, endTime);
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check heartbeat alert",
            traceHeadline = "Check heartbeat alert: {{0}}", timer = "check heartbeat alert")
    private void checkHeartbeatAlert(String agentId, String agentDisplay,
            AlertCondition alertCondition, HeartbeatCondition heartbeatCondition,
            AlertNotification alertNotification) throws Exception {
        alertingService.sendHeartbeatAlertIfNeeded(agentId, agentDisplay, alertCondition,
                heartbeatCondition, alertNotification, false);
    }

    private static boolean isAggregateMetricCondition(AlertCondition alertCondition) {
        if (alertCondition.getValCase() != AlertCondition.ValCase.METRIC_CONDITION) {
            return false;
        }
        String metric = alertCondition.getMetricCondition().getMetric();
        return metric.startsWith("transaction:") || metric.startsWith("error:");
    }

    private static boolean isGaugeMetricCondition(AlertCondition alertCondition) {
        return alertCondition.getValCase() == AlertCondition.ValCase.METRIC_CONDITION
                && alertCondition.getMetricCondition().getMetric().startsWith("gauge:");
    }
}
