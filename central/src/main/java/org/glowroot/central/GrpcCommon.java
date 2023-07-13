/*
 * Copyright 2018-2023 the original author or authors.
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
package org.glowroot.central;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.V09AgentRollupDao;

class GrpcCommon {

    private static final Logger logger = LoggerFactory.getLogger(GrpcCommon.class);

    private final V09AgentRollupDao v09AgentRollupDao;

    GrpcCommon(V09AgentRollupDao v09AgentRollupDao) {
        this.v09AgentRollupDao = v09AgentRollupDao;
    }

    String getAgentIdForLogging(String agentId, boolean postV09) {
        if (postV09) {
            return agentId;
        }
        String postV09AgentId;
        try {
            postV09AgentId = getAgentId(agentId, false);
        } catch (Exception e) {
            logger.error("{} (v09) - {}", agentId, e.getMessage(), e);
            return agentId + " (v09)";
        }
        return postV09AgentId;
    }

    String getAgentId(String agentId, boolean postV09) throws Exception {
        if (postV09) {
            return agentId;
        }
        String v09AgentRollupId = v09AgentRollupDao.getV09AgentRollupId(agentId);
        if (v09AgentRollupId == null) {
            return agentId;
        } else {
            return convertFromV09AgentRollupId(v09AgentRollupId) + agentId;
        }
    }

    static String convertFromV09AgentRollupId(String v09AgentRollupId) {
        // old agent rollup id supported spaces around separator
        return v09AgentRollupId.replaceAll(" */ *", "::").trim() + "::";
    }
}
