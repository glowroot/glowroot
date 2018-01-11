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

import com.google.common.collect.Lists;

import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.GaugeValueDaoImpl;
import org.glowroot.central.v09support.V09Support.Query;
import org.glowroot.central.v09support.V09Support.QueryPlan;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class GaugeValueDaoWithV09Support implements GaugeValueDao {

    private final Set<String> agentRollupIdsWithV09Data;
    private final long v09LastCaptureTime;
    private final Clock clock;
    private final GaugeValueDaoImpl delegate;

    public GaugeValueDaoWithV09Support(Set<String> agentRollupIdsWithV09Data,
            long v09LastCaptureTime, Clock clock, GaugeValueDaoImpl delegate) {
        this.agentRollupIdsWithV09Data = agentRollupIdsWithV09Data;
        this.v09LastCaptureTime = v09LastCaptureTime;
        this.clock = clock;
        this.delegate = delegate;
    }

    @Override
    public void store(String agentId, List<GaugeValue> gaugeValues) throws Exception {
        if (!agentRollupIdsWithV09Data.contains(agentId)) {
            delegate.store(agentId, gaugeValues);
            return;
        }
        List<GaugeValue> gaugeValuesV09 = Lists.newArrayList();
        List<GaugeValue> gaugeValuesPostV09 = Lists.newArrayList();
        for (GaugeValue gaugeValue : gaugeValues) {
            if (gaugeValue.getCaptureTime() <= v09LastCaptureTime) {
                gaugeValuesV09.add(gaugeValue);
            } else {
                gaugeValuesPostV09.add(gaugeValue);
            }
        }
        if (!gaugeValuesV09.isEmpty()) {
            delegate.store(V09Support.convertToV09(agentId),
                    AgentRollupIds.getAgentRollupIds(agentId), gaugeValuesV09);
        }
        if (!gaugeValuesPostV09.isEmpty()) {
            delegate.store(agentId, gaugeValuesPostV09);
        }
    }

    @Override
    public List<Gauge> getRecentlyActiveGauges(String agentRollupId) throws Exception {
        return delegate.getRecentlyActiveGauges(agentRollupId);
    }

    @Override
    public List<Gauge> getGauges(String agentRollupId, long from, long to) throws Exception {
        return delegate.getGauges(agentRollupId, from, to);
    }

    @Override
    public List<GaugeValue> readGaugeValues(String agentRollupId, String gaugeName, long from,
            long to, int rollupLevel) throws Exception {
        QueryPlan plan = V09Support.getPlan(agentRollupIdsWithV09Data, v09LastCaptureTime,
                agentRollupId, from, to);
        Query queryV09 = plan.queryV09();
        Query queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return delegate.readGaugeValues(queryPostV09.agentRollupId(), gaugeName,
                    queryPostV09.from(), queryPostV09.to(), rollupLevel);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return delegate.readGaugeValues(queryV09.agentRollupId(), gaugeName, queryV09.from(),
                    queryV09.to(), rollupLevel);
        } else {
            List<GaugeValue> gaugeValues = Lists.newArrayList();
            gaugeValues.addAll(delegate.readGaugeValues(queryV09.agentRollupId(), gaugeName,
                    queryV09.from(), queryV09.to(), rollupLevel));
            gaugeValues.addAll(delegate.readGaugeValues(queryPostV09.agentRollupId(), gaugeName,
                    queryPostV09.from(), queryPostV09.to(), rollupLevel));
            return gaugeValues;
        }
    }

    @Override
    public void rollup(String agentRollupId) throws Exception {
        delegate.rollup(agentRollupId);
        if (agentRollupIdsWithV09Data.contains(agentRollupId)
                && clock.currentTimeMillis() < v09LastCaptureTime + DAYS.toMillis(30)) {
            delegate.rollup(V09Support.convertToV09(agentRollupId),
                    V09Support.getParentV09(agentRollupId), V09Support.isLeaf(agentRollupId));
        }
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        delegate.truncateAll();
    }
}
