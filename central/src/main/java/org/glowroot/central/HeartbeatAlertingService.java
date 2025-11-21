/*
 * Copyright 2017-2018 the original author or authors.
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

import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.TimeUnit.SECONDS;

class HeartbeatAlertingService {

    private final HeartbeatDao heartbeatDao;
    private final IncidentRepository incidentRepository;
    private final AlertingService alertingService;
    private final ConfigRepository configRepository;

    HeartbeatAlertingService(HeartbeatDao heartbeatDao, IncidentRepository incidentRepository,
            AlertingService alertingService, ConfigRepository configRepository) {
        this.heartbeatDao = heartbeatDao;
        this.incidentRepository = incidentRepository;
        this.alertingService = alertingService;
        this.configRepository = configRepository;
    }

    CompletionStage<?> checkHeartbeatAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime, CassandraProfile profile) {
        long startTime = endTime - SECONDS.toMillis(heartbeatCondition.getTimePeriodSeconds());
        return heartbeatDao.exists(agentRollupId, startTime, endTime, profile).thenCompose(exists -> {
            boolean currentlyTriggered = !exists;
            return sendHeartbeatAlertIfNeeded(agentRollupId, agentRollupDisplay, alertConfig,
                    heartbeatCondition, endTime, currentlyTriggered, profile);
        });
    }

    private CompletionStage<?> sendHeartbeatAlertIfNeeded(String agentRollupId, String agentRollupDisplay,
                                                          AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime,
                                                          boolean currentlyTriggered, CassandraProfile profile) {
        AlertCondition alertCondition = alertConfig.getCondition();
        return incidentRepository.readOpenIncident(agentRollupId,
                alertCondition, alertConfig.getSeverity(), profile).thenCompose(openIncident -> {
            if (openIncident != null && !currentlyTriggered) {
                return incidentRepository.resolveIncident(openIncident, endTime, profile).thenCompose(ignored -> {
                    return sendHeartbeatAlert(agentRollupId, agentRollupDisplay, alertConfig, heartbeatCondition,
                            endTime, true);
                }).thenAccept((ig) -> {});
            } else if (openIncident == null && currentlyTriggered) {
                // the start time for the incident is the end time of the interval evaluated above
                return incidentRepository.insertOpenIncident(agentRollupId, alertCondition,
                        alertConfig.getSeverity(), alertConfig.getNotification(), endTime, profile).thenCompose(ignored -> {
                    return sendHeartbeatAlert(agentRollupId, agentRollupDisplay, alertConfig, heartbeatCondition,
                            endTime, false);
                }).thenAccept((ig) -> {});
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletionStage<?> sendHeartbeatAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime,
            boolean ok) {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Heartbeat";
        StringBuilder sb = new StringBuilder();
        if (ok) {
            sb.append("Receving heartbeat again.\n\n");
        } else {
            sb.append("Heartbeat not received in the last ");
            sb.append(heartbeatCondition.getTimePeriodSeconds());
            sb.append(" seconds.\n\n");
        }
        return configRepository.getCentralAdminGeneralConfig().thenAccept(centralAdminGeneralConfig -> {
            alertingService.sendNotification(centralAdminGeneralConfig.centralDisplayName(), agentRollupId, agentRollupDisplay,
                    alertConfig, endTime, subject, sb.toString(), ok);

        });
    }
}
