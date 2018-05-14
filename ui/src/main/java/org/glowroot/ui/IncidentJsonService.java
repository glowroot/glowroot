/*
 * Copyright 2013-2018 the original author or authors.
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
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.repo.AgentRollupRepository;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.IncidentRepository.ResolvedIncident;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static java.util.concurrent.TimeUnit.DAYS;

@JsonService
class IncidentJsonService {

    private static final String EMBEDDED_AGENT_ID = "";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean central;
    private final IncidentRepository incidentRepository;
    private final ConfigRepository configRepository;
    private final AgentRollupRepository agentRollupRepository;
    private final Clock clock;

    IncidentJsonService(boolean central, IncidentRepository incidentRepository,
            ConfigRepository configRepository, AgentRollupRepository agentRollupRepository,
            Clock clock) {
        this.central = central;
        this.incidentRepository = incidentRepository;
        this.configRepository = configRepository;
        this.agentRollupRepository = agentRollupRepository;
        this.clock = clock;
    }

    @GET(path = "/backend/incidents", permission = "")
    String getIncidents(@BindAuthentication Authentication authentication) throws Exception {
        if (!central && !authentication.isPermitted(EMBEDDED_AGENT_ID, "agent:incident")) {
            throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
        }
        ImmutableIncidentResponse.Builder response = ImmutableIncidentResponse.builder();
        // seems better to read all incidents and then filter by permission, instead of reading
        // individually for every agentRollupId that user has permission to read
        List<OpenIncident> openIncidents =
                new BySeverityOrdering().compound(new ByOpenTimeOrdering())
                        .sortedCopy(incidentRepository.readAllOpenIncidents());
        for (OpenIncident openIncident : openIncidents) {
            if (authentication.isPermittedForAgentRollup(openIncident.agentRollupId(),
                    "agent:incident")) {
                response.addOpenIncidents(createDisplayedIncident(openIncident));
            }
        }
        List<ResolvedIncident> resolvedIncidents = incidentRepository
                .readResolvedIncidents(clock.currentTimeMillis() - DAYS.toMillis(30));
        for (ResolvedIncident resolvedIncident : resolvedIncidents) {
            if (authentication.isPermittedForAgentRollup(resolvedIncident.agentRollupId(),
                    "agent:incident")) {
                response.addResolvedIncidents(createDisplayedIncident(resolvedIncident));
            }
        }
        return mapper.writeValueAsString(response.build());
    }

    private DisplayedIncident createDisplayedIncident(OpenIncident incident) throws Exception {
        return ImmutableDisplayedIncident.builder()
                .agentRollupDisplay(
                        agentRollupRepository.readAgentRollupDisplay(incident.agentRollupId()))
                .openTime(incident.openTime())
                .durationMillis(clock.currentTimeMillis() - incident.openTime())
                .severity(toString(incident.severity()))
                .display(AlertConfigJsonService.getConditionDisplay(incident.agentRollupId(),
                        incident.condition(), configRepository))
                .build();
    }

    private DisplayedIncident createDisplayedIncident(ResolvedIncident incident) throws Exception {
        return ImmutableDisplayedIncident.builder()
                .agentRollupDisplay(
                        agentRollupRepository.readAgentRollupDisplay(incident.agentRollupId()))
                .openTime(incident.openTime())
                .durationMillis(incident.resolveTime() - incident.openTime())
                .resolveTime(incident.resolveTime())
                .severity(toString(incident.severity()))
                .display(AlertConfigJsonService.getConditionDisplay(incident.agentRollupId(),
                        incident.condition(), configRepository))
                .build();
    }

    private static String toString(AlertSeverity alertSeverity) {
        String name = alertSeverity.name();
        return name.substring(0, 1) + name.substring(1).toLowerCase(Locale.ENGLISH);
    }

    private static class BySeverityOrdering extends Ordering<OpenIncident> {
        @Override
        public int compare(OpenIncident left, OpenIncident right) {
            return Ints.compare(left.severity().getNumber(), right.severity().getNumber());
        }
    }

    private static class ByOpenTimeOrdering extends Ordering<OpenIncident> {
        @Override
        public int compare(OpenIncident left, OpenIncident right) {
            return Longs.compare(left.openTime(), right.openTime());
        }
    }

    @Value.Immutable
    interface DisplayedIncident {
        String agentRollupDisplay();
        long openTime();
        long durationMillis();
        @Nullable
        Long resolveTime();
        String severity();
        String display();
    }

    @Value.Immutable
    interface IncidentResponse {
        List<DisplayedIncident> openIncidents();
        List<DisplayedIncident> resolvedIncidents();
    }
}
