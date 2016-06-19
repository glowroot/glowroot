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
package org.glowroot.common.model;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class ServiceCallCollector {

    private final Map<String, Map<String, MutableServiceCall>> serviceCalls = Maps.newHashMap();
    private final int limit;
    private final int maxMultiplierWhileBuilding;

    // this is only used by UI
    private long lastCaptureTime;

    public ServiceCallCollector(int limit, int maxMultiplierWhileBuilding) {
        this.limit = limit;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public List<Aggregate.ServiceCallsByType> toProto() {
        if (serviceCalls.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.ServiceCallsByType> serviceCallsByType = Lists.newArrayList();
        for (Entry<String, Map<String, MutableServiceCall>> entry : serviceCalls.entrySet()) {
            List<Aggregate.ServiceCall> serviceCalls =
                    Lists.newArrayListWithCapacity(entry.getValue().values().size());
            for (MutableServiceCall serviceCall : entry.getValue().values()) {
                serviceCalls.add(serviceCall.toProto());
            }
            if (serviceCalls.size() > limit) {
                order(serviceCalls);
                serviceCalls = serviceCalls.subList(0, limit);
            }
            serviceCallsByType.add(Aggregate.ServiceCallsByType.newBuilder()
                    .setType(entry.getKey())
                    .addAllServiceCall(serviceCalls)
                    .build());
        }
        return serviceCallsByType;
    }

    public void mergeServiceCalls(List<Aggregate.ServiceCallsByType> toBeMergedServiceCalls)
            throws IOException {
        for (Aggregate.ServiceCallsByType toBeMergedServiceCallsByType : toBeMergedServiceCalls) {
            mergeQueries(toBeMergedServiceCallsByType);
        }
    }

    public void mergeQueries(Aggregate.ServiceCallsByType toBeMergedServiceCalls) {
        String type = toBeMergedServiceCalls.getType();
        Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(type);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newHashMap();
            serviceCalls.put(type, serviceCallsForType);
        }
        for (Aggregate.ServiceCall serviceCall : toBeMergedServiceCalls.getServiceCallList()) {
            mergeServiceCall(serviceCall, serviceCallsForType);
        }
    }

    public void mergeServiceCall(String type, String text, double totalDurationNanos,
            long executionCount) {
        Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(type);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newHashMap();
            serviceCalls.put(type, serviceCallsForType);
        }
        mergeServiceCall(text, totalDurationNanos, executionCount, serviceCallsForType);
    }

    private void mergeServiceCall(Aggregate.ServiceCall serviceCall,
            Map<String, MutableServiceCall> serviceCallsForType) {
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(serviceCall.getText());
        if (aggregateServiceCall == null) {
            if (maxMultiplierWhileBuilding != 0
                    && serviceCallsForType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateServiceCall = new MutableServiceCall(serviceCall.getText());
            serviceCallsForType.put(serviceCall.getText(), aggregateServiceCall);
        }
        aggregateServiceCall.addToTotalDurationNanos(serviceCall.getTotalDurationNanos());
        aggregateServiceCall.addToExecutionCount(serviceCall.getExecutionCount());
    }

    private void mergeServiceCall(String text, double totalDurationNanos, long executionCount,
            Map<String, MutableServiceCall> serviceCallsForType) {
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(text);
        if (aggregateServiceCall == null) {
            if (maxMultiplierWhileBuilding != 0
                    && serviceCallsForType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateServiceCall = new MutableServiceCall(text);
            serviceCallsForType.put(text, aggregateServiceCall);
        }
        aggregateServiceCall.addToTotalDurationNanos(totalDurationNanos);
        aggregateServiceCall.addToExecutionCount(executionCount);
    }

    private void order(List<Aggregate.ServiceCall> queries) {
        // reverse sort by total
        Collections.sort(queries, new Comparator<Aggregate.ServiceCall>() {
            @Override
            public int compare(Aggregate.ServiceCall left, Aggregate.ServiceCall right) {
                return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
            }
        });
    }
}
