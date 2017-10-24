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
package org.glowroot.common.repo;

import java.util.List;

import javax.annotation.Nullable;

import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

public interface IncidentRepository {

    void insertOpenIncident(String agentRollupId, AlertCondition condition, AlertSeverity severity,
            AlertNotification notification, long openTime) throws Exception;

    @Nullable
    OpenIncident readOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity) throws Exception;

    List<OpenIncident> readOpenIncidents(String agentRollupId) throws Exception;

    // this is used by UI
    List<OpenIncident> readAllOpenIncidents() throws Exception;

    void resolveIncident(OpenIncident openIncident, long resolveTime) throws Exception;

    List<ResolvedIncident> readResolvedIncidents(long from) throws Exception;

    @Value.Immutable
    interface OpenIncident {
        String agentRollupId();
        long openTime();
        AlertCondition condition();
        AlertSeverity severity();
        AlertNotification notification();
    }

    @Value.Immutable
    interface ResolvedIncident {
        String agentRollupId();
        long openTime();
        long resolveTime();
        AlertCondition condition();
        AlertSeverity severity();
        AlertNotification notification();
    }
}
