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
package org.glowroot.ui;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.config.ImmutableRoleConfig;
import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.AgentRepository.AgentRollup;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.DuplicateRoleNameException;

import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class RoleConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(RoleConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<RoleConfig> orderingByName = new Ordering<RoleConfig>() {
        @Override
        public int compare(RoleConfig left, RoleConfig right) {
            return left.name().compareToIgnoreCase(right.name());
        }
    };

    private final boolean fat;
    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;

    RoleConfigJsonService(boolean fat, ConfigRepository configRepository,
            AgentRepository agentRepository) {
        this.fat = fat;
        this.configRepository = configRepository;
        this.agentRepository = agentRepository;
    }

    @GET(path = "/backend/admin/roles", permission = "admin:view:role")
    String getRoleConfig(@BindRequest RoleConfigRequest request) throws Exception {
        Optional<String> name = request.name();
        if (name.isPresent()) {
            return getRoleConfigInternal(name.get());
        } else {
            List<RoleConfigDto> responses = Lists.newArrayList();
            List<RoleConfig> roleConfigs = configRepository.getRoleConfigs();
            roleConfigs = orderingByName.immutableSortedCopy(roleConfigs);
            for (RoleConfig roleConfig : roleConfigs) {
                responses.add(RoleConfigDto.create(roleConfig));
            }
            return mapper.writeValueAsString(responses);
        }
    }

    @GET(path = "/backend/admin/all-agent-ids", permission = "admin:edit:role")
    String getAllAgentIds() throws Exception {
        return mapper.writeValueAsString(getAllAgentIdsInternal());
    }

    @POST(path = "/backend/admin/roles/add", permission = "admin:edit:role")
    String addRole(@BindRequest RoleConfigDto roleConfigDto) throws Exception {
        RoleConfig roleConfig = roleConfigDto.convert();
        try {
            configRepository.insertRoleConfig(roleConfig);
        } catch (DuplicateRoleNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "name");
        }
        return getRoleConfigInternal(roleConfig.name());
    }

    @POST(path = "/backend/admin/roles/update", permission = "admin:edit:role")
    String updateRole(@BindRequest RoleConfigDto roleConfigDto) throws Exception {
        RoleConfig roleConfig = roleConfigDto.convert();
        String version = roleConfigDto.version().get();
        try {
            configRepository.updateRoleConfig(roleConfig, version);
        } catch (DuplicateRoleNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "name");
        }
        return getRoleConfigInternal(roleConfig.name());
    }

    @POST(path = "/backend/admin/roles/remove", permission = "admin:edit:role")
    void removeRole(@BindRequest RoleConfigRequest request) throws Exception {
        configRepository.deleteRoleConfig(request.name().get());
    }

    private String getRoleConfigInternal(String name) throws Exception {
        RoleConfig roleConfig = configRepository.getRoleConfig(name);
        if (roleConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        ImmutableRoleConfigResponse.Builder response = ImmutableRoleConfigResponse.builder()
                .config(RoleConfigDto.create(roleConfig));
        if (!fat) {
            response.allAgentIds(getAllAgentIdsInternal());
        }
        return mapper.writeValueAsString(response.build());
    }

    private List<String> getAllAgentIdsInternal() throws Exception {
        List<String> agentIds = Lists.newArrayList();
        for (AgentRollup agentRollup : agentRepository.readAgentRollups()) {
            if (agentRollup.leaf()) {
                agentIds.add(agentRollup.name());
            }
        }
        return agentIds;
    }

    @Value.Immutable
    interface RoleConfigRequest {
        Optional<String> name();
    }

    @Value.Immutable
    interface RoleConfigResponse {
        RoleConfigDto config();
        ImmutableList<String> allAgentIds();
    }

    @Value.Immutable
    abstract static class RoleConfigDto {

        abstract String name();
        abstract ImmutableList<String> permissions();
        abstract Optional<String> version(); // absent for insert operations

        private RoleConfig convert() {
            return ImmutableRoleConfig.builder()
                    .name(name())
                    .permissions(permissions())
                    .build();
        }

        private static RoleConfigDto create(RoleConfig roleConfig) {
            return ImmutableRoleConfigDto.builder()
                    .name(roleConfig.name())
                    .permissions(roleConfig.permissions())
                    .version(roleConfig.version())
                    .build();
        }
    }
}
