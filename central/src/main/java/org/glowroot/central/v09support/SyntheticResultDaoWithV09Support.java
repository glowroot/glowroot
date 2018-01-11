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

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.repo.SyntheticResultDaoImpl;
import org.glowroot.central.v09support.V09Support.Query;
import org.glowroot.central.v09support.V09Support.QueryPlan;
import org.glowroot.common.model.SyntheticResult;
import org.glowroot.common.util.Clock;

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
    public void store(String agentRollupId, String syntheticMonitorId, long captureTime,
            long durationNanos, @Nullable String errorMessage) throws Exception {
        if (captureTime <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            delegate.store(V09Support.convertToV09(agentRollupId), syntheticMonitorId, captureTime,
                    durationNanos, errorMessage);
        } else {
            delegate.store(agentRollupId, syntheticMonitorId, captureTime, durationNanos,
                    errorMessage);
        }
    }

    @Override
    public List<SyntheticResult> readSyntheticResults(String agentRollupId,
            String syntheticMonitorId, long from, long to, int rollupLevel) throws Exception {
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
            List<SyntheticResult> list = Lists.newArrayList();
            list.addAll(delegate.readSyntheticResults(queryV09.agentRollupId(), syntheticMonitorId,
                    queryV09.from(), queryV09.to(), rollupLevel));
            list.addAll(delegate.readSyntheticResults(queryPostV09.agentRollupId(),
                    syntheticMonitorId, queryPostV09.from(), queryPostV09.to(), rollupLevel));
            return list;
        }
    }

    @Override
    public void rollup(String agentRollupId) throws Exception {
        delegate.rollup(agentRollupId);
        if (agentRollupIdsWithV09Data.contains(agentRollupId)
                && clock.currentTimeMillis() < v09LastCaptureTime + DAYS.toMillis(30)) {
            delegate.rollup(V09Support.convertToV09(agentRollupId));
        }
    }
}
