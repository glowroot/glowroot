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
package org.glowroot.common.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class ServiceCallCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is the service call type, second key is the service call text
    private final Map<String, Map<String, MutableServiceCall>> serviceCalls = Maps.newHashMap();
    private final int limitPerServiceCallType;

    // this is only used by UI
    private long lastCaptureTime;

    public ServiceCallCollector(int limitPerServiceCallType) {
        this.limitPerServiceCallType = limitPerServiceCallType;
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public Map<String, List<MutableServiceCall>> getSortedAndTruncatedServiceCalls() {
        Map<String, List<MutableServiceCall>> sortedServiceCalls = Maps.newHashMap();
        for (Map.Entry<String, Map<String, MutableServiceCall>> outerEntry : serviceCalls
                .entrySet()) {
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
                    // make copy of limit exceeded bucket since adding exceeded service calls below
                    MutableServiceCall copy = new MutableServiceCall(LIMIT_EXCEEDED_BUCKET);
                    copy.addToTotalDurationNanos(limitExceededBucket.getTotalDurationNanos());
                    copy.addToExecutionCount(limitExceededBucket.getExecutionCount());
                    limitExceededBucket = copy;
                }
                List<MutableServiceCall> serviceCalls =
                        MutableServiceCall.byTotalDurationDesc.sortedCopy(innerMap.values());
                List<MutableServiceCall> exceededServiceCalls =
                        serviceCalls.subList(limitPerServiceCallType, serviceCalls.size());
                serviceCalls = Lists.newArrayList(serviceCalls.subList(0, limitPerServiceCallType));
                for (MutableServiceCall exceededServiceCall : exceededServiceCalls) {
                    limitExceededBucket.add(exceededServiceCall);
                }
                serviceCalls.add(limitExceededBucket);
                // need to re-sort now including limit exceeded bucket
                Collections.sort(serviceCalls, MutableServiceCall.byTotalDurationDesc);
                sortedServiceCalls.put(outerEntry.getKey(), serviceCalls);
            } else {
                sortedServiceCalls.put(outerEntry.getKey(),
                        MutableServiceCall.byTotalDurationDesc.sortedCopy(innerMap.values()));
            }
        }
        return sortedServiceCalls;
    }

    public void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount) {
        Map<String, MutableServiceCall> serviceCallsForType = serviceCalls.get(serviceCallType);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newHashMap();
            serviceCalls.put(serviceCallType, serviceCallsForType);
        }
        mergeServiceCall(serviceCallText, totalDurationNanos, executionCount, serviceCallsForType);
    }

    private static void mergeServiceCall(String serviceCallText, double totalDurationNanos,
            long executionCount, Map<String, MutableServiceCall> serviceCallsForType) {
        MutableServiceCall aggregateServiceCall = serviceCallsForType.get(serviceCallText);
        if (aggregateServiceCall == null) {
            aggregateServiceCall = new MutableServiceCall(serviceCallText);
            serviceCallsForType.put(serviceCallText, aggregateServiceCall);
        }
        aggregateServiceCall.addToTotalDurationNanos(totalDurationNanos);
        aggregateServiceCall.addToExecutionCount(executionCount);
    }
}
