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
package org.glowroot.ui;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.LayoutService.AgentRollupLayout;
import org.glowroot.ui.LayoutService.FilteredAgentRollup;
import org.glowroot.ui.LayoutService.Permissions;

class LayoutJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AgentRollupRepository agentRollupRepository;
    private final LayoutService layoutService;

    LayoutJsonService(AgentRollupRepository agentRollupRepository, LayoutService layoutService) {
        this.agentRollupRepository = agentRollupRepository;
        this.layoutService = layoutService;
    }

    @GET(path = "/backend/agent-rollups", permission = "")
    String getAgentRollups(@BindRequest AgentRollupsRequest agentRollupsRequest,
            @BindAuthentication Authentication authentication) throws Exception {
        List<FilteredAgentRollup> agentRollups =
                filter(agentRollupRepository.readAgentRollups(agentRollupsRequest.from(),
                        agentRollupsRequest.to()), authentication, new Predicate<Permissions>() {
                            @Override
                            public boolean apply(@Nullable Permissions permissions) {
                                return permissions != null && permissions.hasSomeAccess();
                            }
                        });
        List<AgentRollupSmall> dropdown = Lists.newArrayList();
        for (FilteredAgentRollup agentRollup : agentRollups) {
            process(agentRollup, 0, dropdown);
        }
        return mapper.writeValueAsString(dropdown);
    }

    @GET(path = "/backend/agent-rollup", permission = "")
    String getAgentRollup(@BindRequest AgentRollupRequest agentRollupRequest,
            @BindAuthentication Authentication authentication) throws Exception {
        AgentRollupLayout agentRollupLayout =
                layoutService.buildAgentRollupLayout(authentication, agentRollupRequest.id());
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

    // need to filter out agent rollups with no access rights, and move children up if needed
    static List<FilteredAgentRollup> filter(List<AgentRollup> agentRollups,
            Authentication authentication, Predicate<Permissions> filterFn) throws Exception {
        List<FilteredAgentRollup> filtered = Lists.newArrayList();
        for (AgentRollup agentRollup : agentRollups) {
            Permissions permissions =
                    LayoutService.getPermissions(authentication, agentRollup.id());
            if (filterFn.apply(permissions)) {
                filtered.add(ImmutableFilteredAgentRollup.builder()
                        .id(agentRollup.id())
                        .display(agentRollup.display())
                        .lastDisplayPart(agentRollup.lastDisplayPart())
                        .addAllChildren(filter(agentRollup.children(), authentication, filterFn))
                        .permissions(permissions)
                        .build());
            } else {
                // move children (if they are accessible themselves) up to this level
                filtered.addAll(filter(agentRollup.children(), authentication, filterFn));
            }
        }
        // re-sort in case any children were moved up to this level
        return new FilteredAgentRollupOrdering().sortedCopy(filtered);
    }

    private static void process(FilteredAgentRollup agentRollup, int depth,
            List<AgentRollupSmall> dropdown) throws Exception {
        AgentRollupSmall agentRollupLayout = ImmutableAgentRollupSmall.builder()
                .id(agentRollup.id())
                .display(agentRollup.display())
                .lastDisplayPart(agentRollup.lastDisplayPart())
                .depth(depth)
                .build();
        dropdown.add(agentRollupLayout);
        for (FilteredAgentRollup childAgentRollup : agentRollup.children()) {
            process(childAgentRollup, depth + 1, dropdown);
        }
    }

    @Value.Immutable
    interface AgentRollupSmall {
        String id();
        String display();
        String lastDisplayPart();
        int depth();
    }

    @Value.Immutable
    interface AgentRollupsRequest {
        long from();
        long to();
    }

    @Value.Immutable
    interface AgentRollupRequest {
        String id();
    }

    private static class FilteredAgentRollupOrdering extends Ordering<FilteredAgentRollup> {
        @Override
        public int compare(FilteredAgentRollup left, FilteredAgentRollup right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }
}
