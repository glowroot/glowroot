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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class ServiceCallCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is the service call type, second key is the service call text
    private final Map<String, Map<String, MutableServiceCall>> serviceCalls = Maps.newHashMap();
    private final Map<String, MutableServiceCall> limitExceededBuckets = Maps.newHashMap();
    private final int limit;
    private final int hardLimitMultiplierWhileBuilding;

    private int serviceCallCount;

    public ServiceCallCollector(int limit, int hardLimitMultiplierWhileBuilding) {
        this.limit = limit;
        this.hardLimitMultiplierWhileBuilding = hardLimitMultiplierWhileBuilding;
    }

    public List<Aggregate.ServiceCall> toAggregateProto() {
        // " + serviceCalls.size()" is to cover the maximum number of limit exceeded buckets
        List<Aggregate.ServiceCall> allServiceCalls = Lists
                .newArrayListWithCapacity(Math.min(serviceCallCount, limit) + serviceCalls.size());
        for (Map.Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls
                .entrySet()) {
            for (Map.Entry<String, MutableServiceCall> innerEntry : outerEntry.getValue()
                    .entrySet()) {
                allServiceCalls.add(innerEntry.getValue().toAggregateProto(outerEntry.getKey(),
                        innerEntry.getKey()));
            }
        }
        if (allServiceCalls.size() <= limit) {
            // there could be limit exceeded buckets if hardLimitMultiplierWhileBuilding is 1
            for (Map.Entry<String, MutableServiceCall> entry : limitExceededBuckets.entrySet()) {
                allServiceCalls.add(
                        entry.getValue().toAggregateProto(entry.getKey(), LIMIT_EXCEEDED_BUCKET));
            }
            sort(allServiceCalls);
            return allServiceCalls;
        }
        sort(allServiceCalls);
        List<Aggregate.ServiceCall> exceededServiceCalls =
                allServiceCalls.subList(limit, allServiceCalls.size());
        allServiceCalls = Lists.newArrayList(allServiceCalls.subList(0, limit));
        // do not modify original limit exceeded buckets since adding exceeded queries below
        Map<String, MutableServiceCall> limitExceededBuckets = copyLimitExceededBuckets();
        for (Aggregate.ServiceCall exceededServiceCall : exceededServiceCalls) {
            String queryType = exceededServiceCall.getType();
            MutableServiceCall limitExceededBucket = limitExceededBuckets.get(queryType);
            if (limitExceededBucket == null) {
                limitExceededBucket = new MutableServiceCall();
                limitExceededBuckets.put(queryType, limitExceededBucket);
            }
            limitExceededBucket.add(exceededServiceCall);
        }
        for (Map.Entry<String, MutableServiceCall> entry : limitExceededBuckets.entrySet()) {
            allServiceCalls
                    .add(entry.getValue().toAggregateProto(entry.getKey(), LIMIT_EXCEEDED_BUCKET));
        }
        // need to re-sort now including limit exceeded bucket
        sort(allServiceCalls);
        return allServiceCalls;
    }

    public void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount) {
        Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(serviceCallType);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newHashMap();
            serviceCalls.put(serviceCallType, serviceCallsForType);
        }
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(serviceCallText);
        if (aggregateServiceCall == null) {
            if (serviceCallCount < limit * hardLimitMultiplierWhileBuilding) {
                aggregateServiceCall = new MutableServiceCall();
                serviceCallsForType.put(serviceCallText, aggregateServiceCall);
                serviceCallCount++;
            } else {
                aggregateServiceCall = getOrCreateLimitExceededBucket(serviceCallType);
            }
        }
        aggregateServiceCall.addToTotalDurationNanos(totalDurationNanos);
        aggregateServiceCall.addToExecutionCount(executionCount);
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

    private Map<String, MutableServiceCall> copyLimitExceededBuckets() {
        Map<String, MutableServiceCall> copies = Maps.newHashMap();
        for (Map.Entry<String, MutableServiceCall> entry : limitExceededBuckets.entrySet()) {
            String serviceCallType = entry.getKey();
            MutableServiceCall limitExceededBucket = entry.getValue();
            MutableServiceCall copy = new MutableServiceCall();
            copy.add(limitExceededBucket);
            copies.put(serviceCallType, copy);
        }
        return copies;
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
