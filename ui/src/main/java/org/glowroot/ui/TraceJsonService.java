/*
 * Copyright 2011-2017 the original author or authors.
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

import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@JsonService
class TraceJsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceJsonService.class);

    private final TraceCommonService traceCommonService;

    TraceJsonService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    // see special case for "agent:trace" permission in Authentication.isPermitted()
    @GET(path = "/backend/trace/header", permission = "agent:trace")
    String getHeader(@BindAgentId String agentId, @BindRequest HeaderRequest request)
            throws Exception {
        String headerJson = traceCommonService.getHeaderJson(agentId, request.traceId(),
                request.checkLiveTraces());
        if (headerJson == null) {
            logger.debug("no trace found for agent id '{}' and trace id '{}'", agentId,
                    request.traceId());
            return "{\"expired\":true}";
        } else {
            return headerJson;
        }
    }

    @Value.Immutable
    abstract static class HeaderRequest {
        abstract String traceId();
        @Value.Default
        boolean checkLiveTraces() {
            return false;
        }
    }
}
