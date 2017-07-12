/*
 * Copyright 2016-2017 the original author or authors.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import org.immutables.value.Value;

import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.ObjectMappers;

@JsonService
class AgentConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigRepository configRepository;
    private final AgentRollupRepository agentRollupRepository;

    AgentConfigJsonService(ConfigRepository configRepository,
            AgentRollupRepository agentRollupRepository) {
        this.configRepository = configRepository;
        this.agentRollupRepository = agentRollupRepository;
    }

    @GET(path = "/backend/admin/agent-rollups", permission = "admin:view:agentRollup")
    String getAgentRollup(@BindRequest AgentRollupRequest request) throws Exception {
        if (request.agentRollupId().isPresent()) {
            String agentRollupId = request.agentRollupId().get();
            return mapper.writeValueAsString(ImmutableAgentRollupResponse.builder()
                    .id(agentRollupId)
                    .display(agentRollupRepository.readAgentRollupDisplay(agentRollupId))
                    .build());
        } else {
            return mapper.writeValueAsString(agentRollupRepository.readAgentRollups());
        }
    }

    @POST(path = "/backend/admin/agent-rollups/remove", permission = "admin:edit:agentRollup")
    void removeAgentRollup(@BindRequest AgentRollupRequest request) throws Exception {
        configRepository.deleteAgentRollup(request.agentRollupId().get());
    }

    @Value.Immutable
    interface AgentRollupRequest {
        Optional<String> agentRollupId();
    }

    @Value.Immutable
    interface AgentRollupResponse {
        String id();
        String display();
    }
}
