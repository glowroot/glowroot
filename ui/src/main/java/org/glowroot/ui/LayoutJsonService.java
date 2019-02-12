/*
 * Copyright 2017-2019 the original author or authors.
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

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.config.RoleConfig.HasAnyPermission;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.glowroot.common2.repo.ActiveAgentRepository.TopLevelAgentRollup;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.LayoutService.AgentRollupLayout;
import org.glowroot.ui.LayoutService.FilteredChildAgentRollup;
import org.glowroot.ui.LayoutService.FilteredTopLevelAgentRollup;

class LayoutJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ActiveAgentRepository activeAgentRepository;
    private final LayoutService layoutService;

    LayoutJsonService(ActiveAgentRepository activeAgentRepository, LayoutService layoutService) {
        this.activeAgentRepository = activeAgentRepository;
        this.layoutService = layoutService;
    }

    @GET(path = "/backend/top-level-agent-rollups", permission = "")
    String getTopLevelAgentRollups(@BindRequest TopLevelAgentRollupsRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        List<TopLevelAgentRollup> topLevelAgentRollups =
                activeAgentRepository.readActiveTopLevelAgentRollups(request.from(), request.to());
        List<FilteredTopLevelAgentRollup> filtered = Lists.newArrayList();
        for (TopLevelAgentRollup topLevelAgentRollup : topLevelAgentRollups) {
            HasAnyPermission hasAnyPermission =
                    authentication.hasAnyPermissionForAgentRollup(topLevelAgentRollup.id());
            if (hasAnyPermission != HasAnyPermission.NO) {
                filtered.add(ImmutableFilteredTopLevelAgentRollup.builder()
                        .id(topLevelAgentRollup.id())
                        .display(topLevelAgentRollup.display())
                        .disabled(hasAnyPermission == HasAnyPermission.ONLY_IN_CHILD)
                        .build());
            }
        }
        return mapper.writeValueAsString(filtered);
    }

    @GET(path = "/backend/child-agent-rollups", permission = "")
    String getChildAgentRollups(@BindRequest ChildAgentRollupsRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        List<FilteredChildAgentRollup> childAgentRollups =
                filterChildAgentRollups(
                        activeAgentRepository.readActiveChildAgentRollups(
                                request.topLevelId(), request.from(), request.to()),
                        authentication);
        List<AgentRollupSmall> dropdown = Lists.newArrayList();
        for (FilteredChildAgentRollup childAgentRollup : childAgentRollups) {
            flatten(childAgentRollup, 0, dropdown);
        }
        return mapper.writeValueAsString(dropdown);
    }

    @GET(path = "/backend/agent-rollup", permission = "")
    String getAgentRollup(@BindRequest AgentRollupRequest request,
            @BindAuthentication Authentication authentication) throws Exception {
        AgentRollupLayout agentRollupLayout =
                layoutService.buildAgentRollupLayout(authentication, request.id());
        if (agentRollupLayout == null) {
            // FIXME let user know that UI configuration not found
            return "{}";
        }
        if (!agentRollupLayout.permissions().hasSomeAccess()) {
            // FIXME return no-access AgentRollupLayout
            return "{}";
        }
        return mapper.writeValueAsString(agentRollupLayout);
    }

    // need to filter out agent child rollups with no access rights
    private static List<FilteredChildAgentRollup> filterChildAgentRollups(
            List<AgentRollup> agentRollups, Authentication authentication) throws Exception {
        List<FilteredChildAgentRollup> filtered = Lists.newArrayList();
        for (AgentRollup agentRollup : agentRollups) {
            HasAnyPermission hasAnyPermission =
                    authentication.hasAnyPermissionForAgentRollup(agentRollup.id());
            if (hasAnyPermission != HasAnyPermission.NO) {
                filtered.add(ImmutableFilteredChildAgentRollup.builder()
                        .id(agentRollup.id())
                        .display(agentRollup.display())
                        .lastDisplayPart(agentRollup.lastDisplayPart())
                        .disabled(hasAnyPermission == HasAnyPermission.ONLY_IN_CHILD)
                        .addAllChildren(filterChildAgentRollups(agentRollup.children(),
                                authentication))
                        .build());
            }
        }
        return filtered;
    }

    private static void flatten(FilteredChildAgentRollup filteredChildAgentRollup, int depth,
            List<AgentRollupSmall> dropdown) throws Exception {
        AgentRollupSmall agentRollupLayout = ImmutableAgentRollupSmall.builder()
                .id(filteredChildAgentRollup.id())
                .display(filteredChildAgentRollup.display())
                .lastDisplayPart(filteredChildAgentRollup.lastDisplayPart())
                .disabled(filteredChildAgentRollup.disabled())
                .depth(depth)
                .build();
        dropdown.add(agentRollupLayout);
        for (FilteredChildAgentRollup child : filteredChildAgentRollup.children()) {
            flatten(child, depth + 1, dropdown);
        }
    }

    @Value.Immutable
    interface AgentRollupSmall {
        String id();
        String display(); // when this is used for child dropdown, this is the child display (not
                          // including the top level display)
        String lastDisplayPart();
        boolean disabled();
        int depth();
    }

    @Value.Immutable
    interface TopLevelAgentRollupsRequest {
        long from();
        long to();
    }

    @Value.Immutable
    interface ChildAgentRollupsRequest {
        String topLevelId();
        long from();
        long to();
    }

    @Value.Immutable
    interface AgentRollupRequest {
        String id();
    }
}
