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
package org.glowroot.central.repo;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

public class AgentRollupIds {

    private AgentRollupIds() {}

    // includes agentRollupId itself
    // agentRollupId is index 0
    // its direct parent is index 1
    // etc...
    public static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = new ArrayList<>();
        agentRollupIds.add(agentRollupId);
        int separatorLen = "::".length();
        int lastFoundIndex;
        if (agentRollupId.endsWith("::")) {
            lastFoundIndex = agentRollupId.length() - separatorLen;
        } else {
            lastFoundIndex = agentRollupId.length();
        }
        int nextFoundIndex;
        // -1 because the agent rollup part must be at least 1 character
        while ((nextFoundIndex =
                agentRollupId.lastIndexOf("::", lastFoundIndex - separatorLen - 1)) != -1) {
            if (nextFoundIndex == 0) {
                break;
            }
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex + separatorLen));
            lastFoundIndex = nextFoundIndex;
        }
        return agentRollupIds;
    }

    static @Nullable String getParent(String agentRollupId) {
        int index;
        if (agentRollupId.endsWith("::")) {
            index = agentRollupId.lastIndexOf("::", agentRollupId.length() - 5);
        } else {
            index = agentRollupId.lastIndexOf("::");
        }
        if (index == -1 || index == 0) {
            return null;
        }
        return agentRollupId.substring(0, index + 2);
    }
}
