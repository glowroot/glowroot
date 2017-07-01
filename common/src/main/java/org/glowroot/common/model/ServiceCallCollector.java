/*
 * Copyright 2016-2017 the original author or authors.
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class ServiceCallCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    private final Map<String, Map<String, MutableServiceCall>> serviceCalls = Maps.newHashMap();
    private final Map<String, MutableServiceCall> limitExceededBuckets = Maps.newHashMap();
    private final int limitPerServiceCallType;
    private final int maxMultiplierWhileBuilding;

    // this is only used by UI
    private long lastCaptureTime;

    public ServiceCallCollector(int limitPerServiceCallType, int maxMultiplierWhileBuilding) {
        this.limitPerServiceCallType = limitPerServiceCallType;
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
        for (Entry<String, List<MutableServiceCall>> entry : getSortedAndTruncatedQueries()
                .entrySet()) {
            Aggregate.ServiceCallsByType.Builder builder = Aggregate.ServiceCallsByType.newBuilder()
                    .setType(entry.getKey());
            for (MutableServiceCall serviceCall : entry.getValue()) {
                builder.addServiceCall(serviceCall.toProto());
            }
            serviceCallsByType.add(builder.build());
        }
        return serviceCallsByType;
    }

    private Map<String, List<MutableServiceCall>> getSortedAndTruncatedQueries() {
        Map<String, List<MutableServiceCall>> sortedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls.entrySet()) {
            Map<String, MutableServiceCall> innerMap = outerEntry.getValue();
            if (innerMap.size() > limitPerServiceCallType) {
                MutableServiceCall limitExceededBucket = innerMap.get(LIMIT_EXCEEDED_BUCKET);
                if (limitExceededBucket == null) {
                    limitExceededBucket = new MutableServiceCall(LIMIT_EXCEEDED_BUCKET);
                } else {
                    // make copy to not modify original
                    innerMap = Maps.newHashMap(innerMap);
                    // remove temporarily so it is not included in initial sort/truncation
                    innerMap.remove(LIMIT_EXCEEDED_BUCKET);
                }
                List<MutableServiceCall> queries =
                        MutableServiceCall.byTotalDurationDesc.sortedCopy(innerMap.values());
                List<MutableServiceCall> exceededQueries =
                        queries.subList(limitPerServiceCallType, queries.size());
                queries = Lists.newArrayList(queries.subList(0, limitPerServiceCallType));
                for (MutableServiceCall exceededServiceCall : exceededQueries) {
                    limitExceededBucket.addTo(exceededServiceCall);
                }
                queries.add(limitExceededBucket);
                // need to re-sort now including limit exceeded bucket
                Collections.sort(queries, MutableServiceCall.byTotalDurationDesc);
                sortedQueries.put(outerEntry.getKey(), queries);
            } else {
                sortedQueries.put(outerEntry.getKey(),
                        MutableServiceCall.byTotalDurationDesc.sortedCopy(innerMap.values()));
            }
        }
        return sortedQueries;
    }

    public void mergeServiceCalls(List<Aggregate.ServiceCallsByType> toBeMergedServiceCalls) {
        for (Aggregate.ServiceCallsByType toBeMergedServiceCallsByType : toBeMergedServiceCalls) {
            String serviceCallType = toBeMergedServiceCallsByType.getType();
            Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(serviceCallType);
            if (serviceCallsForType == null) {
                serviceCallsForType = Maps.newHashMap();
                serviceCalls.put(serviceCallType, serviceCallsForType);
            }
            for (Aggregate.ServiceCall serviceCall : toBeMergedServiceCallsByType
                    .getServiceCallList()) {
                mergeServiceCall(serviceCallType, serviceCall.getText(),
                        serviceCall.getTotalDurationNanos(), serviceCall.getExecutionCount(),
                        serviceCallsForType);
            }
        }
    }

    public void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount) {
        Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(serviceCallType);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newHashMap();
            serviceCalls.put(serviceCallType, serviceCallsForType);
        }
        mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos, executionCount,
                serviceCallsForType);
    }

    private void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount,
            Map<String, MutableServiceCall> serviceCallsForType) {
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(serviceCallText);
        if (aggregateServiceCall == null) {
            if (maxMultiplierWhileBuilding == 0
                    || serviceCallsForType.size() < limitPerServiceCallType
                            * maxMultiplierWhileBuilding) {
                aggregateServiceCall = new MutableServiceCall(serviceCallText);
                serviceCallsForType.put(serviceCallText, aggregateServiceCall);
            } else {
                aggregateServiceCall = limitExceededBuckets.get(serviceCallType);
                if (aggregateServiceCall == null) {
                    aggregateServiceCall = new MutableServiceCall(LIMIT_EXCEEDED_BUCKET);
                    limitExceededBuckets.put(serviceCallType, aggregateServiceCall);
                }
            }
        }
        aggregateServiceCall.addToTotalDurationNanos(totalDurationNanos);
        aggregateServiceCall.addToExecutionCount(executionCount);
    }
}
