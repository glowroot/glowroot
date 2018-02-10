/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class ServiceCallCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is the service call type, second key is the service call text
    private final Map<String, Map<String, MutableServiceCall>> serviceCalls = Maps.newHashMap();
    private final Map<String, MutableServiceCall> limitExceededBuckets = Maps.newHashMap();
    private final int limitPerServiceCallType;
    private final int maxMultiplierWhileBuilding;

    public ServiceCallCollector(int limitPerServiceCallType, int maxMultiplierWhileBuilding) {
        this.limitPerServiceCallType = limitPerServiceCallType;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public List<Aggregate.ServiceCallsByType> toAggregateProto() {
        if (serviceCalls.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.ServiceCallsByType> proto = Lists.newArrayList();
        for (Map.Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls
                .entrySet()) {
            Map<String, MutableServiceCall> innerMap = outerEntry.getValue();
            // + 1 is for possible limit exceeded bucket added at the end before final sorting
            List<Aggregate.ServiceCall> serviceCalls =
                    Lists.newArrayListWithCapacity(innerMap.values().size() + 1);
            for (Map.Entry<String, MutableServiceCall> innerEntry : innerMap.entrySet()) {
                serviceCalls.add(innerEntry.getValue().toAggregateProto(innerEntry.getKey()));
            }
            // need to check equality in case maxMultiplierWhileBuilding is 1
            if (serviceCalls.size() >= limitPerServiceCallType) {
                sort(serviceCalls);
                List<Aggregate.ServiceCall> exceededServiceCalls =
                        serviceCalls.subList(limitPerServiceCallType, serviceCalls.size());
                serviceCalls = Lists.newArrayList(serviceCalls.subList(0, limitPerServiceCallType));
                MutableServiceCall limitExceededBucket =
                        limitExceededBuckets.get(outerEntry.getKey());
                if (limitExceededBucket == null) {
                    limitExceededBucket = new MutableServiceCall();
                } else {
                    // make copy of limit exceeded bucket since adding exceeded service calls below
                    MutableServiceCall copy = new MutableServiceCall();
                    copy.add(limitExceededBucket);
                    limitExceededBucket = copy;
                }
                for (Aggregate.ServiceCall exceededServiceCall : exceededServiceCalls) {
                    limitExceededBucket
                            .addToTotalDurationNanos(exceededServiceCall.getTotalDurationNanos());
                    limitExceededBucket
                            .addToExecutionCount(exceededServiceCall.getExecutionCount());
                }
                serviceCalls.add(limitExceededBucket.toAggregateProto(LIMIT_EXCEEDED_BUCKET));
                // need to re-sort now including the limit exceeded bucket
                sort(serviceCalls);
            }
            proto.add(Aggregate.ServiceCallsByType.newBuilder()
                    .setType(outerEntry.getKey())
                    .addAllServiceCall(serviceCalls)
                    .build());
        }
        return proto;
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

    public void mergeServiceCallsInto(ServiceCallCollector collector) {
        for (Map.Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls
                .entrySet()) {
            for (Map.Entry<String, MutableServiceCall> entry : outerEntry.getValue().entrySet()) {
                MutableServiceCall serviceCall = entry.getValue();
                collector.mergeServiceCall(outerEntry.getKey(), entry.getKey(),
                        serviceCall.getTotalDurationNanos(), serviceCall.getExecutionCount());
            }
        }
        for (Map.Entry<String, MutableServiceCall> limitExceededBucket : limitExceededBuckets
                .entrySet()) {
            collector.mergeLimitExceededBucket(limitExceededBucket.getKey(),
                    limitExceededBucket.getValue());
        }
    }

    public void mergeServiceCallsInto(org.glowroot.common.model.ServiceCallCollector collector) {
        for (Map.Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls
                .entrySet()) {
            for (Map.Entry<String, MutableServiceCall> entry : outerEntry.getValue().entrySet()) {
                MutableServiceCall serviceCall = entry.getValue();
                collector.mergeServiceCall(outerEntry.getKey(), entry.getKey(),
                        serviceCall.getTotalDurationNanos(), serviceCall.getExecutionCount());
            }
        }
        for (Map.Entry<String, MutableServiceCall> limitExceededBucket : limitExceededBuckets
                .entrySet()) {
            MutableServiceCall serviceCall = limitExceededBucket.getValue();
            collector.mergeServiceCall(limitExceededBucket.getKey(), LIMIT_EXCEEDED_BUCKET,
                    serviceCall.getTotalDurationNanos(), serviceCall.getExecutionCount());
        }
    }

    private void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount,
            Map<String, MutableServiceCall> serviceCallsForType) {
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(serviceCallText);
        if (aggregateServiceCall == null) {
            if (serviceCallsForType.size() < limitPerServiceCallType * maxMultiplierWhileBuilding) {
                aggregateServiceCall = new MutableServiceCall();
                serviceCallsForType.put(serviceCallText, aggregateServiceCall);
            } else {
                aggregateServiceCall = getOrCreateLimitExceededBucket(serviceCallType);
            }
        }
        aggregateServiceCall.addToTotalDurationNanos(totalDurationNanos);
        aggregateServiceCall.addToExecutionCount(executionCount);
    }

    private void mergeLimitExceededBucket(String serviceCallType,
            MutableServiceCall limitExceededBucket) {
        MutableServiceCall serviceCall = getOrCreateLimitExceededBucket(serviceCallType);
        serviceCall.add(limitExceededBucket);
    }

    private MutableServiceCall getOrCreateLimitExceededBucket(String serviceCallType) {
        MutableServiceCall serviceCall = limitExceededBuckets.get(serviceCallType);
        if (serviceCall == null) {
            serviceCall = new MutableServiceCall();
            limitExceededBuckets.put(serviceCallType, serviceCall);
        }
        return serviceCall;
    }

    private static void sort(List<Aggregate.ServiceCall> serviceCalls) {
        // reverse sort by total
        Collections.sort(serviceCalls, new Comparator<Aggregate.ServiceCall>() {
            @Override
            public int compare(Aggregate.ServiceCall left, Aggregate.ServiceCall right) {
                return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
            }
        });
    }
}
