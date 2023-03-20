/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.common2.repo;

import java.util.List;

import org.immutables.value.Value;

public interface ActiveAgentRepository {

    List<TopLevelAgentRollup> readActiveTopLevelAgentRollups(long from, long to) throws Exception;

    List<AgentRollup> readActiveChildAgentRollups(String topLevelId, long from, long to)
            throws Exception;

    List<AgentRollup> readRecentlyActiveAgentRollups(long lastXMillis) throws Exception;

    List<AgentRollup> readActiveAgentRollups(long from, long to) throws Exception;

    @Value.Immutable
    interface TopLevelAgentRollup { // used for dropdown display
        String id();
        String display();
    }

    // used for dropdown display
    // used for rollup work and for agent dropdown in role config and report
    @Value.Immutable
    interface AgentRollup {
        String id();
        // when returned from readActiveChildAgentRollups (for use in child agent dropdown), this is
        // the child display (not including the top level display)
        String display();
        String lastDisplayPart();
        List<AgentRollup> children();
    }
}
