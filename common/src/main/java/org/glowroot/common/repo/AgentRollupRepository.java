/*
 * Copyright 2015-2017 the original author or authors.
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

import org.immutables.value.Value;

import org.glowroot.common.util.Styles;

public interface AgentRollupRepository {

    List<AgentRollup> readRecentlyActiveAgentRollups(int lastXDays) throws Exception;

    List<AgentRollup> readAgentRollups(long from, long to) throws Exception;

    String readAgentRollupDisplay(String agentRollupId) throws Exception;

    List<String> readAgentRollupDisplayParts(String agentRollupId) throws Exception;

    @Value.Immutable
    @Styles.AllParameters
    interface AgentRollup {
        String id();
        String display();
        String lastDisplayPart();
        List<AgentRollup> children();
    }
}
