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
package org.glowroot.central;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import org.glowroot.central.storage.AgentDao;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class LiveTraceRepositoryImpl implements LiveTraceRepository {

    private final DownstreamServiceImpl downstreamService;
    private final AgentDao agentDao;

    LiveTraceRepositoryImpl(DownstreamServiceImpl downstreamService,
            AgentDao agentDao) {
        this.downstreamService = downstreamService;
        this.agentDao = agentDao;
    }

    @Override
    public @Nullable Trace.Header getHeader(String agentRollup, String agentId, String traceId)
            throws Exception {
        checkValidAgentIdForRequest(agentRollup, agentId);
        return downstreamService.getHeader(agentId, traceId);
    }

    @Override
    public @Nullable Entries getEntries(String agentRollup, String agentId, String traceId)
            throws Exception {
        checkValidAgentIdForRequest(agentRollup, agentId);
        return downstreamService.getEntries(agentId, traceId);
    }

    @Override
    public @Nullable Profile getMainThreadProfile(String agentRollup, String agentId,
            String traceId) throws Exception {
        checkValidAgentIdForRequest(agentRollup, agentId);
        return downstreamService.getMainThreadProfile(agentId, traceId);
    }

    @Override
    public @Nullable Profile getAuxThreadProfile(String agentRollup, String agentId, String traceId)
            throws Exception {
        checkValidAgentIdForRequest(agentRollup, agentId);
        return downstreamService.getAuxThreadProfile(agentId, traceId);
    }

    @Override
    public @Nullable Trace getFullTrace(String agentRollup, String agentId, String traceId)
            throws Exception {
        checkValidAgentIdForRequest(agentRollup, agentId);
        return downstreamService.getFullTrace(agentId, traceId);
    }

    @Override
    public int getMatchingTraceCount(String transactionType, @Nullable String transactionName) {
        return 0;
    }

    @Override
    public List<TracePoint> getMatchingActiveTracePoints(TraceKind traceKind,
            String transactionType, @Nullable String transactionName, TracePointFilter filter,
            int limit, long captureTime, long captureTick) {
        return ImmutableList.of();
    }

    @Override
    public List<TracePoint> getMatchingPendingPoints(TraceKind traceKind, String transactionType,
            @Nullable String transactionName, TracePointFilter filter, long captureTime) {
        return ImmutableList.of();
    }

    private void checkValidAgentIdForRequest(String agentRollup, String agentId) {
        if (!agentDao.readAgentRollups(agentId).contains(agentRollup)) {
            throw new IllegalArgumentException(
                    "Agent " + agentId + " is not a child of rollup " + agentRollup);
        }
    }
}
