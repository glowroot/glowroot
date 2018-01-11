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
package org.glowroot.central.v09support;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.immutables.value.Value;

class V09Support {

    // includes agentId itself
    // agentId is index 0
    // its direct parent is index 1
    // etc...
    static List<String> getAgentRollupIdsV09(String agentId) {
        List<String> agentRollupIds = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentId.indexOf("::", lastFoundIndex)) != -1) {
            agentRollupIds.add(agentId.substring(0, nextFoundIndex).replace("::", "/"));
            lastFoundIndex = nextFoundIndex + 2;
        }
        Collections.reverse(agentRollupIds);
        if (lastFoundIndex == -1) {
            // no rollups
            agentRollupIds.add(0, agentId);
        } else {
            agentRollupIds.add(0, agentId.substring(lastFoundIndex));
        }
        return agentRollupIds;
    }

    static @Nullable String getParentV09(String agentRollupId) {
        int index = agentRollupId.lastIndexOf("::", agentRollupId.length() - 3);
        if (index == -1) {
            return null;
        } else {
            return agentRollupId.substring(0, index).replace("::", "/");
        }
    }

    static String convertToV09(String agentRollupId) {
        if (agentRollupId.endsWith("::")) {
            return agentRollupId.substring(0, agentRollupId.length() - 2).replace("::", "/");
        }
        int index = agentRollupId.lastIndexOf("::");
        if (index == -1) {
            return agentRollupId;
        } else {
            return agentRollupId.substring(index + 2);
        }
    }

    static boolean isLeaf(String agentRollupId) {
        return !agentRollupId.endsWith("::");
    }

    static QueryPlan getPlan(Set<String> v09AgentRollupIds, long v09LastCaptureTime,
            String agentRollupId, long from, long to) {
        if (from <= v09LastCaptureTime
                && v09AgentRollupIds.contains(agentRollupId)) {
            if (to <= v09LastCaptureTime) {
                return ImmutableQueryPlan.builder()
                        .queryV09(ImmutableQuery.builder()
                                .agentRollupId(V09Support.convertToV09(agentRollupId))
                                .from(from)
                                .to(to)
                                .build())
                        .build();
            } else {
                return ImmutableQueryPlan.builder()
                        .queryV09(ImmutableQuery.builder()
                                .agentRollupId(V09Support.convertToV09(agentRollupId))
                                .from(from)
                                .to(v09LastCaptureTime)
                                .build())
                        .queryPostV09(ImmutableQuery.builder()
                                .agentRollupId(agentRollupId)
                                .from(v09LastCaptureTime + 1)
                                .to(to)
                                .build())
                        .build();
            }
        } else {
            return ImmutableQueryPlan.builder()
                    .queryPostV09(ImmutableQuery.builder()
                            .agentRollupId(agentRollupId)
                            .from(from)
                            .to(to)
                            .build())
                    .build();
        }
    }

    @Value.Immutable
    interface QueryPlan {
        @Nullable
        Query queryV09();
        @Nullable
        Query queryPostV09();
    }

    @Value.Immutable
    interface Query {
        String agentRollupId();
        long from();
        long to();
    }
}
