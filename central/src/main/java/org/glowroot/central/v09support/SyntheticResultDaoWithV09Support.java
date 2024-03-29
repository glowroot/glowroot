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
package org.glowroot.central.v09support;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.repo.SyntheticResultDaoImpl;
import org.glowroot.central.v09support.V09Support.Query;
import org.glowroot.central.v09support.V09Support.QueryPlan;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.SyntheticResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class SyntheticResultDaoWithV09Support implements SyntheticResultDao {

    private final Set<String> agentRollupIdsWithV09Data;
    private final long v09LastCaptureTime;
    private final Clock clock;
    private final SyntheticResultDaoImpl delegate;

    public SyntheticResultDaoWithV09Support(Set<String> agentRollupIdsWithV09Data,
                                            long v09LastCaptureTime, Clock clock, SyntheticResultDaoImpl delegate) {
        this.agentRollupIdsWithV09Data = agentRollupIdsWithV09Data;
        this.v09LastCaptureTime = v09LastCaptureTime;
        this.clock = clock;
        this.delegate = delegate;
    }

    // synthetic result records are not rolled up to their parent, but are stored directly for
    // rollups that have their own synthetic monitors defined
    @Override
    public CompletionStage<?> store(String agentRollupId, String syntheticMonitorId,
                      String syntheticMonitorDisplay, long captureTime, long durationNanos,
                      @Nullable String errorMessage) throws Exception {
        if (captureTime <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            return delegate.store(V09Support.convertToV09(agentRollupId), syntheticMonitorId,
                    syntheticMonitorDisplay, captureTime, durationNanos, errorMessage);
        } else {
            return delegate.store(agentRollupId, syntheticMonitorId, syntheticMonitorDisplay, captureTime,
                    durationNanos, errorMessage);
        }
    }

    @Override
    public Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to)
            throws Exception {
        return delegate.getSyntheticMonitorIds(agentRollupId, from, to);
    }

    @Override
    public CompletionStage<List<SyntheticResult>> readSyntheticResults(String agentRollupId,
                                                                       String syntheticMonitorId, long from, long to, int rollupLevel) {
        QueryPlan plan = V09Support.getPlan(agentRollupIdsWithV09Data, v09LastCaptureTime,
                agentRollupId, from, to);
        Query queryV09 = plan.queryV09();
        Query queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return delegate.readSyntheticResults(queryPostV09.agentRollupId(), syntheticMonitorId,
                    queryPostV09.from(), queryPostV09.to(), rollupLevel);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return delegate.readSyntheticResults(queryV09.agentRollupId(), syntheticMonitorId,
                    queryV09.from(), queryV09.to(), rollupLevel);
        } else {
            return delegate.readSyntheticResults(queryV09.agentRollupId(), syntheticMonitorId,
                    queryV09.from(), queryV09.to(), rollupLevel).thenCombine(delegate.readSyntheticResults(queryPostV09.agentRollupId(),
                    syntheticMonitorId, queryPostV09.from(), queryPostV09.to(), rollupLevel), (list1, list2) -> {
                List<SyntheticResult> list = new ArrayList<>();
                list.addAll(list1);
                list.addAll(list2);
                return list;
            });
        }
    }

    @Override
    public CompletionStage<List<SyntheticResultRollup0>> readLastFromRollup0(String agentRollupId,
                                                                             String syntheticMonitorId, int x) {
        return delegate.readLastFromRollup0(agentRollupId, syntheticMonitorId, x);
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return delegate.rollup(agentRollupId).thenCompose(ignored -> {
            if (agentRollupIdsWithV09Data.contains(agentRollupId)
                    && clock.currentTimeMillis() < v09LastCaptureTime + DAYS.toMillis(30)) {
                return delegate.rollup(V09Support.convertToV09(agentRollupId));
            }
            return CompletableFuture.completedFuture(null);
        });
    }
}
