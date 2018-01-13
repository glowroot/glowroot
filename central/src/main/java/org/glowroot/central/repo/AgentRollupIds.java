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
package org.glowroot.central.repo;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

public class AgentRollupIds {

    private AgentRollupIds() {}

    // includes agentRollupId itself
    // agentRollupId is index 0
    // its direct parent is index 1
    // etc...
    public static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = Lists.newArrayList();
        agentRollupIds.add(agentRollupId);
        int lastFoundIndex = agentRollupId.length() - 3;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollupId.lastIndexOf("::", lastFoundIndex)) != -1) {
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex + 2));
            lastFoundIndex = nextFoundIndex - 1;
        }
        return agentRollupIds;
    }

    static @Nullable String getParent(String agentRollupId) {
        int index;
        if (agentRollupId.endsWith("::")) {
            index = agentRollupId.lastIndexOf("::", agentRollupId.length() - 3);
        } else {
            index = agentRollupId.lastIndexOf("::");
        }
        if (index == -1) {
            return null;
        }
        return agentRollupId.substring(0, index + 2);
    }
}
