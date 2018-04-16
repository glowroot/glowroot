/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.impl.Transaction.RootTimerCollector;
import org.glowroot.agent.model.AsyncQueryData;
import org.glowroot.agent.model.AsyncTimerImpl;
import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.agent.model.ImmutableTimerImplSnapshot;
import org.glowroot.agent.model.MutableAggregateTimer;
import org.glowroot.agent.model.MutableTraceTimer;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.ServiceCallCollector;
import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.TimerName;

class AsyncComponents {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // async root timers are the root timers which do not have corresponding thread context
    // (those corresponding to async trace entries)
    private final Object asyncTimerLock = new Object();
    @GuardedBy("asyncTimerLock")
    private @MonotonicNonNull List<AsyncTimerImpl> asyncTimers;
    @GuardedBy("asyncTimerLock")
    private @MonotonicNonNull Map<String, AggregateAsyncTimer> alreadyMergedAsyncTimers;

    private final int maxQueryAggregates;
    private final int maxServiceCallAggregates;

    private final Ticker ticker;

    private final Map<String, Map<String, AsyncQueryData>> asyncQueries = Maps.newConcurrentMap();
    private final Map<String, Map<String, AsyncQueryData>> asyncServiceCalls =
            Maps.newConcurrentMap();

    // not using AtomicInteger since slight overcounting is ok
    private volatile int queryAggregateCounter;
    private volatile int serviceCallAggregateCounter;

    AsyncComponents(int maxQueryAggregates, int maxServiceCallAggregates, Ticker ticker) {
        this.maxQueryAggregates = maxQueryAggregates;
        this.maxServiceCallAggregates = maxServiceCallAggregates;
        this.ticker = ticker;
    }

    void mergeAsyncTimersInto(RootTimerCollector rootTimers) {
        synchronized (asyncTimerLock) {
            if (asyncTimers == null) {
                return;
            }
            if (alreadyMergedAsyncTimers != null) {
                for (Map.Entry<String, AggregateAsyncTimer> entry : alreadyMergedAsyncTimers
                        .entrySet()) {
                    AggregateAsyncTimer value = entry.getValue();
                    rootTimers.mergeRootTimer(
                            new SimpleTimerImpl(entry.getKey(), value.totalNanos, value.count));
                }
            }
            for (AsyncTimerImpl asyncTimer : asyncTimers) {
                rootTimers.mergeRootTimer(asyncTimer);
            }
        }
    }

    void mergeQueriesInto(QueryCollector collector) {
        for (Map.Entry<String, Map<String, AsyncQueryData>> outerEntry : asyncQueries.entrySet()) {
            String queryType = outerEntry.getKey();
            for (Map.Entry<String, AsyncQueryData> innerEntry : outerEntry.getValue().entrySet()) {
                AsyncQueryData queryData = innerEntry.getValue();
                collector.mergeQuery(queryType, queryData.getQueryText(),
                        queryData.getTotalDurationNanos(ticker), queryData.getExecutionCount(),
                        queryData.hasTotalRows(), queryData.getTotalRows(), queryData.isActive());
            }
        }
    }

    void mergeServiceCallsInto(ServiceCallCollector collector) {
        for (Map.Entry<String, Map<String, AsyncQueryData>> outerEntry : asyncServiceCalls
                .entrySet()) {
            String serviceCallType = outerEntry.getKey();
            for (Map.Entry<String, AsyncQueryData> innerEntry : outerEntry.getValue().entrySet()) {
                AsyncQueryData queryData = innerEntry.getValue();
                collector.mergeServiceCall(serviceCallType, queryData.getQueryText(),
                        queryData.getTotalDurationNanos(ticker), queryData.getExecutionCount());
            }
        }
    }

    AsyncTimerImpl startAsyncTimer(TimerName asyncTimerName, long startTick) {
        AsyncTimerImpl asyncTimer = new AsyncTimerImpl((TimerNameImpl) asyncTimerName, startTick);
        synchronized (asyncTimerLock) {
            if (asyncTimers == null) {
                asyncTimers = Lists.newArrayList();
            }
            if (asyncTimers.size() >= 1000) {
                // this is just to conserve memory
                if (alreadyMergedAsyncTimers == null) {
                    alreadyMergedAsyncTimers = Maps.newHashMap();
                }
                List<AsyncTimerImpl> activeAsyncTimers = Lists.newArrayList();
                for (AsyncTimerImpl loopAsyncTimer : asyncTimers) {
                    if (loopAsyncTimer.active()) {
                        activeAsyncTimers.add(loopAsyncTimer);
                        continue;
                    }
                    AggregateAsyncTimer aggregateAsyncTimer =
                            alreadyMergedAsyncTimers.get(loopAsyncTimer.getName());
                    if (aggregateAsyncTimer == null) {
                        aggregateAsyncTimer = new AggregateAsyncTimer();
                        alreadyMergedAsyncTimers.put(loopAsyncTimer.getName(), aggregateAsyncTimer);
                    }
                    aggregateAsyncTimer.totalNanos += loopAsyncTimer.getTotalNanos();
                    aggregateAsyncTimer.count += loopAsyncTimer.getCount();
                }
                asyncTimers = activeAsyncTimers;
            }
            asyncTimers.add(asyncTimer);
        }
        return asyncTimer;
    }

    AsyncQueryData getOrCreateAsyncQueryData(String queryType, String queryText,
            boolean bypassLimit) {
        Map<String, AsyncQueryData> queriesForType = asyncQueries.get(queryType);
        if (queriesForType == null) {
            queriesForType = Maps.newConcurrentMap();
            asyncQueries.put(queryType, queriesForType);
        }
        AsyncQueryData queryData = queriesForType.get(queryText);
        if (queryData == null) {
            queryData = createQueryData(queriesForType, queryText, bypassLimit);
            queriesForType.put(queryText, queryData);
        }
        return queryData;
    }

    private AsyncQueryData createQueryData(Map<String, AsyncQueryData> queriesForType,
            String queryText, boolean bypassLimit) {
        if (allowAnotherQueryAggregate(bypassLimit)) {
            return createQueryData(queriesForType, queryText);
        } else {
            AsyncQueryData limitExceededBucket = queriesForType.get(LIMIT_EXCEEDED_BUCKET);
            if (limitExceededBucket == null) {
                limitExceededBucket = createQueryData(queriesForType, LIMIT_EXCEEDED_BUCKET);
            }
            return new AsyncQueryData(queryText, limitExceededBucket);
        }
    }

    AsyncQueryData getOrCreateAsyncServiceCallData(String serviceCallType, String serviceCallText,
            boolean bypassLimit) {
        Map<String, AsyncQueryData> serviceCallsForType = asyncServiceCalls.get(serviceCallType);
        if (serviceCallsForType == null) {
            serviceCallsForType = Maps.newConcurrentMap();
            asyncServiceCalls.put(serviceCallType, serviceCallsForType);
        }
        AsyncQueryData serviceCallData = serviceCallsForType.get(serviceCallText);
        if (serviceCallData == null) {
            serviceCallData =
                    createServiceCallData(serviceCallsForType, serviceCallText, bypassLimit);
            serviceCallsForType.put(serviceCallText, serviceCallData);
        }
        return serviceCallData;
    }

    private AsyncQueryData createServiceCallData(Map<String, AsyncQueryData> serviceCallsForType,
            String serviceCallText, boolean bypassLimit) {
        if (allowAnotherServiceCallAggregate(bypassLimit)) {
            return createServiceCallData(serviceCallsForType, serviceCallText);
        } else {
            AsyncQueryData limitExceededBucket = serviceCallsForType.get(LIMIT_EXCEEDED_BUCKET);
            if (limitExceededBucket == null) {
                limitExceededBucket =
                        createServiceCallData(serviceCallsForType, LIMIT_EXCEEDED_BUCKET);
            }
            return new AsyncQueryData(serviceCallText, limitExceededBucket);
        }
    }

    // this method has side effect of incrementing counter
    private boolean allowAnotherQueryAggregate(boolean bypassLimit) {
        return queryAggregateCounter++ < maxQueryAggregates
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                || bypassLimit;
    }

    // this method has side effect of incrementing counter
    private boolean allowAnotherServiceCallAggregate(boolean bypassLimit) {
        return serviceCallAggregateCounter++ < maxServiceCallAggregates
                * AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER
                || bypassLimit;
    }

    private static AsyncQueryData createQueryData(Map<String, AsyncQueryData> queriesForType,
            String queryText) {
        AsyncQueryData queryData = new AsyncQueryData(queryText, null);
        queriesForType.put(queryText, queryData);
        return queryData;
    }

    private static AsyncQueryData createServiceCallData(
            Map<String, AsyncQueryData> serviceCallsForType, String serviceCallText) {
        AsyncQueryData serviceCallData = new AsyncQueryData(serviceCallText, null);
        serviceCallsForType.put(serviceCallText, serviceCallData);
        return serviceCallData;
    }

    private static class AggregateAsyncTimer {

        private long totalNanos;
        private long count;
    }

    private static class SimpleTimerImpl implements CommonTimerImpl {

        private final String name;
        private final long totalNanos;
        private final long count;

        private SimpleTimerImpl(String name, long totalNanos, long count) {
            this.name = name;
            this.totalNanos = totalNanos;
            this.count = count;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isExtended() {
            return false;
        }

        @Override
        public long getTotalNanos() {
            return totalNanos;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public void mergeChildTimersInto(List<MutableTraceTimer> childTimers) {
            // async timers have no child timers
        }

        @Override
        public void mergeChildTimersInto2(List<MutableAggregateTimer> childTimers) {
            // async timers have no child timers
        }

        @Override
        public TimerImplSnapshot getSnapshot() {
            return ImmutableTimerImplSnapshot.builder()
                    .totalNanos(totalNanos)
                    .count(count)
                    .active(false)
                    .build();
        }
    }
}
