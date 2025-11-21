/*
 * Copyright 2017-2023 the original author or authors.
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

import com.google.common.base.Stopwatch;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.agent.api.Instrumentation.AlreadyInTransactionBehavior;
import org.glowroot.central.repo.AlertingDisabledDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class CentralAlertingService {

    private static final Logger logger = LoggerFactory.getLogger(CentralAlertingService.class);

    private final ConfigRepositoryImpl configRepository;
    private final AlertingService alertingService;
    private final HeartbeatAlertingService heartbeatAlertingService;
    private final AlertingDisabledDao alertingDisabledDao;
    private final Clock clock;

    private final ExecutorService workerExecutor;

    private final Stopwatch stopwatch = Stopwatch.createStarted();

    private volatile boolean closed;

    CentralAlertingService(ConfigRepositoryImpl configRepository, AlertingService alertingService,
                           HeartbeatAlertingService heartbeatAlertingService,
                           AlertingDisabledDao alertingDisabledDao, Clock clock) {
        this.configRepository = configRepository;
        this.alertingService = alertingService;
        this.heartbeatAlertingService = heartbeatAlertingService;
        this.alertingDisabledDao = alertingDisabledDao;
        this.clock = clock;
        workerExecutor = MoreExecutors2.newCachedThreadPool("Alert-Async-Worker-%d");
    }

    void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to alert checking thread
        workerExecutor.shutdownNow();
        if (!workerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for alert checking thread to terminate");
        }
    }

    CompletionStage<?> checkForDeletedAlerts(String agentRollupId, CassandraProfile profile) {
        return alertingService.checkForDeletedAlerts(agentRollupId, profile);
    }

    CompletionStage<?> checkForAllDeletedAlerts(CassandraProfile profile) {
        return alertingService.checkForAllDeletedAlerts(profile);
    }

    CompletionStage<?> checkAggregateAlertsAsync(String agentId, String agentDisplay, long endTime, CassandraProfile profile) {

        return isCurrentlyDisabled(agentId, profile).thenCompose(disabled -> {
            if (disabled) {
                return CompletableFuture.completedFuture(null);
            }
            return configRepository.getAlertConfigsNonBlocking(agentId);
        }).thenCompose((alertConfigs) -> {
            List<AlertConfig> aggregateAlertConfigs = new ArrayList<>();
            for (AlertConfig alertConfig : alertConfigs) {
                if (isAggregateCondition(alertConfig.getCondition())) {
                    aggregateAlertConfigs.add(alertConfig);
                }
            }
            if (!aggregateAlertConfigs.isEmpty()) {
                return checkAlertsAsync(agentId, agentDisplay, endTime, aggregateAlertConfigs, profile);
            }
            return CompletableFuture.completedFuture(null);
        }).exceptionally(throwable -> {
            if (throwable instanceof AgentConfigNotFoundException) {
                // be lenient if agent_config table is messed up
                logger.debug(throwable.getMessage(), throwable);
                return null;
            }
            logger.error("{} - {}", agentId, throwable.getMessage(), throwable);
            return null;
        });
    }

    CompletionStage<?> checkGaugeAndHeartbeatAlertsAsync(String agentId, String agentDisplay, long endTime, CassandraProfile profile) {
        return isCurrentlyDisabled(agentId, profile).thenCompose(disabled -> {
            if (disabled) {
                return CompletableFuture.completedFuture(null);
            }
            return configRepository.getAlertConfigsNonBlocking(agentId);
        }).thenCompose((alertConfigs) -> {
            List<AlertConfig> gaugeAndHeartbeatAlertConfigs = new ArrayList<>();
            for (AlertConfig alertConfig : alertConfigs) {
                AlertCondition condition = alertConfig.getCondition();
                if (isGaugeCondition(condition)
                        || condition.getValCase() == AlertCondition.ValCase.HEARTBEAT_CONDITION) {
                    gaugeAndHeartbeatAlertConfigs.add(alertConfig);
                }
            }
            if (!gaugeAndHeartbeatAlertConfigs.isEmpty()) {
                return checkAlertsAsync(agentId, agentDisplay, endTime, gaugeAndHeartbeatAlertConfigs, profile);
            }
            return CompletableFuture.completedFuture(null);
        }).exceptionally(throwable -> {
            if (throwable instanceof AgentConfigNotFoundException) {
                // be lenient if agent_config table is messed up
                logger.debug(throwable.getMessage(), throwable);
                return null;
            }
            logger.error("{} - {}", agentId, throwable.getMessage(), throwable);
            return null;
        });
    }

    @Instrumentation.Transaction(transactionType = "Background", transactionName = "Check alert",
            traceHeadline = "Check alerts: {{0}}", timer = "check alerts",
            alreadyInTransactionBehavior = AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION)
    CompletionStage<?> checkAggregateAndGaugeAndHeartbeatAlertsAsync(String agentRollupId,
                                                                     String agentRollupDisplay, long endTime, CassandraProfile profile) {

        return isCurrentlyDisabled(agentRollupId, profile).thenCompose(disabled -> {
            if (disabled) {
                return CompletableFuture.completedFuture(null);
            }
            return configRepository.getAlertConfigsNonBlocking(agentRollupId);
        }).thenCompose((alertConfigs) -> {
            List<AlertConfig> aggregateAndGaugeAndHeartbeatAlertConfigs = new ArrayList<>();
            for (AlertConfig alertConfig : alertConfigs) {
                AlertCondition condition = alertConfig.getCondition();
                if (condition.getValCase() == AlertCondition.ValCase.METRIC_CONDITION
                        || condition.getValCase() == AlertCondition.ValCase.HEARTBEAT_CONDITION) {
                    aggregateAndGaugeAndHeartbeatAlertConfigs.add(alertConfig);
                }
            }
            if (!aggregateAndGaugeAndHeartbeatAlertConfigs.isEmpty()) {
                return checkAlertsAsync(agentRollupId, agentRollupDisplay, endTime,
                        aggregateAndGaugeAndHeartbeatAlertConfigs, profile);
            }
            return CompletableFuture.completedFuture(null);
        }).exceptionally(throwable -> {
            if (throwable instanceof AgentConfigNotFoundException) {
                // be lenient if agent_config table is messed up
                logger.debug(throwable.getMessage(), throwable);
                return null;
            }
            logger.error("{} - {}", agentRollupId, throwable.getMessage(), throwable);
            return null;
        });
    }

    private CompletionStage<Boolean> isCurrentlyDisabled(String agentRollupId, CassandraProfile profile) {
        return alertingDisabledDao.getAlertingDisabledUntilTime(agentRollupId, profile).thenApply(disabledUntilTime -> {
            return disabledUntilTime != null && disabledUntilTime > clock.currentTimeMillis();
        });
    }

    private CompletionStage<?> checkAlertsAsync(String agentRollupId, String agentRollupDisplay, long endTime,
                                                List<AlertConfig> alertConfigs, CassandraProfile profile) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletionStage<?>> futures = new ArrayList<>();
        for (AlertConfig alertConfig : alertConfigs) {
            try {
                futures.add(checkAlert(agentRollupId, agentRollupDisplay, endTime, alertConfig, profile));
            } catch (Throwable t) {
                logger.error("{} - {}", agentRollupId, t.getMessage(), t);
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletionStage<?> checkAlert(String agentRollupId, String agentDisplay, long endTime,
                                          AlertConfig alertConfig, CassandraProfile profile) {
        return configRepository.getCentralAdminGeneralConfig().thenCompose(centralAdminGeneralConfig -> {
            AlertCondition alertCondition = alertConfig.getCondition();
            switch (alertCondition.getValCase()) {
                case METRIC_CONDITION:
                    return alertingService.checkMetricAlert(
                            centralAdminGeneralConfig.centralDisplayName(),
                            agentRollupId, agentDisplay, alertConfig,
                            alertCondition.getMetricCondition(), endTime, profile).thenAccept(ig -> {});
                case HEARTBEAT_CONDITION:
                    if (stopwatch.elapsed(MINUTES) >= 4) {
                        // give agents plenty of time to re-connect after central start-up, needs to be
                        // at least enough time for grpc max reconnect backoff which is 2 minutes
                        // +/- 20% jitter (see io.grpc.internal.ExponentialBackoffPolicy) but better to
                        // give a bit extra (4 minutes above) to avoid false heartbeat alert
                        return heartbeatAlertingService.checkHeartbeatAlert(agentRollupId, agentDisplay,
                                alertConfig, alertCondition.getHeartbeatCondition(), endTime, profile).thenAccept(ig -> {});
                    }
                    break;
                default:
                    throw new IllegalStateException(
                            "Unexpected alert condition: " + alertCondition.getValCase().name());
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private static boolean isAggregateCondition(AlertCondition alertCondition) {
        if (alertCondition.getValCase() != AlertCondition.ValCase.METRIC_CONDITION) {
            return false;
        }
        String metric = alertCondition.getMetricCondition().getMetric();
        return metric.startsWith("transaction:") || metric.startsWith("error:");
    }

    private static boolean isGaugeCondition(AlertCondition alertCondition) {
        return alertCondition.getValCase() == AlertCondition.ValCase.METRIC_CONDITION
                && alertCondition.getMetricCondition().getMetric().startsWith("gauge:");
    }
}
