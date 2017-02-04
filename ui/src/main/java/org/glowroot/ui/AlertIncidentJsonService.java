/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.common.repo.TriggeredAlertRepository.TriggeredAlert;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;

@JsonService
class AlertIncidentJsonService {

    private static final Logger logger = LoggerFactory.getLogger(AlertIncidentJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TriggeredAlertRepository triggeredAlertRepository;
    private final ConfigRepository configRepository;

    AlertIncidentJsonService(TriggeredAlertRepository triggeredAlertRepository,
            ConfigRepository configRepository) {
        this.triggeredAlertRepository = triggeredAlertRepository;
        this.configRepository = configRepository;
    }

    // seems better to read all alerts and then filter by permission, instead of reading
    // individually for every agentRollupId that user has permission to read
    @GET(path = "/backend/alerts", permission = "")
    String getAlerts(@BindAuthentication Authentication authentication) throws Exception {
        List<TriggeredAlert> triggeredAlerts = triggeredAlertRepository.readAll();
        List<AlertItem> alertItems = Lists.newArrayList();
        for (TriggeredAlert triggeredAlert : triggeredAlerts) {
            if (!authentication.isAgentPermitted(triggeredAlert.agentRollupId(), "agent:alert")) {
                continue;
            }
            AlertConfig alertConfig = configRepository
                    .getAlertConfig(triggeredAlert.agentRollupId(), triggeredAlert.alertId());
            if (alertConfig == null) {
                logger.warn("could not find alert config in {}: {}", triggeredAlert.agentRollupId(),
                        triggeredAlert.alertId());
                alertItems.add(ImmutableAlertItem.of(triggeredAlert.agentRollupId(), "<UNKNOWN>"));
            } else {
                alertItems.add(ImmutableAlertItem.of(triggeredAlert.agentRollupId(),
                        AlertConfigJsonService.getAlertDisplay(triggeredAlert.agentRollupId(),
                                alertConfig, configRepository)));
            }
        }
        return mapper.writeValueAsString(alertItems);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface AlertItem {
        String agentRollupId();
        String display();
    }
}
