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

import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.common.repo.IncidentRepository.OpenIncident;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;

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

    void checkHeartbeatAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime)
            throws Exception {
        long startTime = endTime - SECONDS.toMillis(heartbeatCondition.getTimePeriodSeconds());
        boolean currentlyTriggered = !heartbeatDao.exists(agentRollupId, startTime, endTime);
        sendHeartbeatAlertIfNeeded(agentRollupId, agentRollupDisplay, alertConfig,
                heartbeatCondition, endTime, currentlyTriggered);
    }

    private void sendHeartbeatAlertIfNeeded(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime,
            boolean currentlyTriggered) throws Exception {
        AlertCondition alertCondition = alertConfig.getCondition();
        OpenIncident openIncident = incidentRepository.readOpenIncident(agentRollupId,
                alertCondition, alertConfig.getSeverity());
        if (openIncident != null && !currentlyTriggered) {
            incidentRepository.resolveIncident(openIncident, endTime);
            sendHeartbeatAlert(agentRollupId, agentRollupDisplay, alertConfig, heartbeatCondition,
                    endTime, true);
        } else if (openIncident == null && currentlyTriggered) {
            // the start time for the incident is the end time of the interval evaluated above
            incidentRepository.insertOpenIncident(agentRollupId, alertCondition,
                    alertConfig.getSeverity(), alertConfig.getNotification(), endTime);
            sendHeartbeatAlert(agentRollupId, agentRollupDisplay, alertConfig, heartbeatCondition,
                    endTime, false);
        }
    }

    private void sendHeartbeatAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, HeartbeatCondition heartbeatCondition, long endTime,
            boolean ok) throws Exception {
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
        String centralDisplay =
                configRepository.getCentralAdminGeneralConfig().centralDisplayName();
        alertingService.sendNotification(centralDisplay, agentRollupId, agentRollupDisplay,
                alertConfig, endTime, subject, sb.toString(), ok);
    }
}
