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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.TraceDaoImpl;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.model.Result;
import org.glowroot.common.repo.ImmutableErrorMessageCount;
import org.glowroot.common.repo.ImmutableErrorMessageResult;
import org.glowroot.common.repo.ImmutableTraceQuery;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class TraceDaoWithV09Support implements TraceDao {

    private final Set<String> agentRollupIdsWithV09Data;
    private final long v09LastCaptureTime;
    private final long v09FqtLastExpirationTime;
    private final Clock clock;
    private final TraceDaoImpl delegate;

    // key is v09 agent rollup id, value is post v09 agent rollup id
    private final Map<String, String> convertToPostV09AgentIds;

    public TraceDaoWithV09Support(Set<String> agentRollupIdsWithV09Data, long v09LastCaptureTime,
            long v09FqtLastExpirationTime, Clock clock, TraceDaoImpl delegate) {
        this.agentRollupIdsWithV09Data = agentRollupIdsWithV09Data;
        this.v09LastCaptureTime = v09LastCaptureTime;
        this.v09FqtLastExpirationTime = v09FqtLastExpirationTime;
        this.clock = clock;
        this.delegate = delegate;
        convertToPostV09AgentIds = Maps.newHashMap();
        for (String agentRollupIdWithV09Data : agentRollupIdsWithV09Data) {
            if (!agentRollupIdWithV09Data.endsWith("::")) {
                convertToPostV09AgentIds.put(V09Support.convertToV09(agentRollupIdWithV09Data),
                        agentRollupIdWithV09Data);
            }
        }
    }

    @Override
    public void store(String agentId, Trace trace) throws Exception {
        if (trace.getHeader().getCaptureTime() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentId)) {
            delegate.store(V09Support.convertToV09(agentId),
                    V09Support.getAgentRollupIdsV09(agentId),
                    AgentRollupIds.getAgentRollupIds(agentId), trace);
        } else {
            delegate.store(agentId, trace);
        }
    }

    @Override
    public Result<TracePoint> readSlowPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        return splitResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readSlowPoints(id, q, filter, limit));
    }

    @Override
    public Result<TracePoint> readErrorPoints(String agentRollupId, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        return splitResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readErrorPoints(id, q, filter, limit));
    }

    @Override
    public long readSlowCount(String agentRollupId, TraceQuery query) throws Exception {
        return splitCountIfNeeded(agentRollupId, query, (id, q) -> delegate.readSlowCount(id, q));
    }

    @Override
    public long readErrorCount(String agentRollupId, TraceQuery query) throws Exception {
        return splitCountIfNeeded(agentRollupId, query, (id, q) -> delegate.readErrorCount(id, q));
    }

    @Override
    public ErrorMessageResult readErrorMessages(String agentRollupId, TraceQuery query,
            ErrorMessageFilter filter, long resolutionMillis, int limit) throws Exception {
        return splitErrorMessageResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readErrorMessages(id, q, filter, resolutionMillis, limit));
    }

    @Override
    public @Nullable HeaderPlus readHeaderPlus(String agentId, String traceId) throws Exception {
        HeaderPlus headerPlus = delegate.readHeaderPlus(agentId, traceId);
        if (headerPlus == null && checkV09(agentId, traceId)) {
            headerPlus = delegate.readHeaderPlus(V09Support.convertToV09(agentId), traceId);
        }
        return headerPlus;
    }

    @Override
    public Entries readEntries(String agentId, String traceId) throws Exception {
        Entries entries = delegate.readEntries(agentId, traceId);
        if (entries.entries().isEmpty() && checkV09(agentId, traceId)) {
            return delegate.readEntries(agentId, traceId);
        }
        return entries;
    }

    @Override
    public Entries readEntriesForExport(String agentId, String traceId) throws Exception {
        Entries entries = delegate.readEntriesForExport(agentId, traceId);
        if (entries.entries().isEmpty() && clock.currentTimeMillis() < v09FqtLastExpirationTime
                && checkV09(agentId, traceId)) {
            return delegate.readEntriesForExport(agentId, traceId);
        }
        return entries;
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String agentId, String traceId)
            throws Exception {
        Profile profile = delegate.readMainThreadProfile(agentId, traceId);
        if (profile == null && checkV09(agentId, traceId)) {
            profile = delegate.readMainThreadProfile(V09Support.convertToV09(agentId), traceId);
        }
        return profile;
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String agentId, String traceId) throws Exception {
        Profile profile = delegate.readAuxThreadProfile(agentId, traceId);
        if (profile == null && checkV09(agentId, traceId)) {
            profile = delegate.readAuxThreadProfile(V09Support.convertToV09(agentId), traceId);
        }
        return profile;
    }

    private boolean checkV09(String agentId, String traceId) throws Exception {
        if (!agentRollupIdsWithV09Data.contains(agentId)) {
            return false;
        }
        HeaderPlus headerPlusV09 =
                delegate.readHeaderPlus(V09Support.convertToV09(agentId), traceId);
        return headerPlusV09 != null
                && headerPlusV09.header().getCaptureTime() <= v09LastCaptureTime;
    }

    private Result<TracePoint> splitResultIfNeeded(String agentRollupId, TraceQuery query,
            int limit, DelegateResultAction action) throws Exception {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        TraceQuery queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return action.result(agentRollupId, queryPostV09);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return convertFromV09(action.result(V09Support.convertToV09(agentRollupId), queryV09));
        } else {
            Result<TracePoint> resultV09 =
                    convertFromV09(action.result(V09Support.convertToV09(agentRollupId), queryV09));
            Result<TracePoint> resultPostV09 = action.result(agentRollupId, queryPostV09);
            List<TracePoint> tracePoints = Lists.newArrayList();
            tracePoints.addAll(resultV09.records());
            tracePoints.addAll(resultPostV09.records());
            if (tracePoints.size() > limit) {
                tracePoints = TraceDaoImpl
                        .applyLimitByDurationNanosAndThenSortByCaptureTime(tracePoints, limit);
                return new Result<>(tracePoints, true);
            } else {
                tracePoints = Ordering.from(Comparator.comparingLong(TracePoint::captureTime))
                        .sortedCopy(tracePoints);
                return new Result<>(tracePoints,
                        resultV09.moreAvailable() || resultPostV09.moreAvailable());
            }
        }
    }

    private Result<TracePoint> convertFromV09(Result<TracePoint> resultV09) {
        List<TracePoint> tracePoints = Lists.newArrayList();
        for (TracePoint tracePoint : resultV09.records()) {
            String agentId = convertToPostV09AgentIds.get(tracePoint.agentId());
            if (agentId == null) {
                // this shouldn't happen
                tracePoints.add(tracePoint);
            } else {
                tracePoints.add(ImmutableTracePoint.builder()
                        .copyFrom(tracePoint)
                        .agentId(agentId)
                        .build());
            }
        }
        return new Result<>(tracePoints, resultV09.moreAvailable());
    }

    private ErrorMessageResult splitErrorMessageResultIfNeeded(String agentRollupId,
            TraceQuery query, int limit, DelegateErrorMessageResultAction action) throws Exception {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        TraceQuery queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return action.result(agentRollupId, queryPostV09);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return action.result(V09Support.convertToV09(agentRollupId), queryV09);
        } else {
            ErrorMessageResult resultV09 =
                    action.result(V09Support.convertToV09(agentRollupId), queryV09);
            ErrorMessageResult resultPostV09 = action.result(agentRollupId, queryPostV09);
            List<ErrorMessagePoint> points = Lists.newArrayList();
            points.addAll(resultV09.points());
            points.addAll(resultPostV09.points());
            Map<String, MutableLong> messageCounts = Maps.newHashMap();
            Result<ErrorMessageCount> countsV09 = resultV09.counts();
            Result<ErrorMessageCount> countsPostV09 = resultPostV09.counts();
            for (ErrorMessageCount errorMessageCount : countsV09.records()) {
                messageCounts.computeIfAbsent(errorMessageCount.message(), k -> new MutableLong())
                        .add(errorMessageCount.count());
            }
            for (ErrorMessageCount errorMessageCount : countsPostV09.records()) {
                messageCounts.computeIfAbsent(errorMessageCount.message(), k -> new MutableLong())
                        .add(errorMessageCount.count());
            }
            List<ErrorMessageCount> counts = messageCounts.entrySet().stream()
                    .map(e1 -> ImmutableErrorMessageCount.of(e1.getKey(), e1.getValue().value))
                    .sorted(Comparator.comparing(ErrorMessageCount::count).reversed())
                    // explicit type on this line is needed for Checker Framework
                    // see https://github.com/typetools/checker-framework/issues/531
                    .collect(Collectors.<ErrorMessageCount>toList());
            if (counts.size() > limit) {
                return ImmutableErrorMessageResult.builder()
                        .addAllPoints(points)
                        .counts(new Result<>(counts.subList(0, limit), true))
                        .build();
            } else {
                return ImmutableErrorMessageResult.builder()
                        .addAllPoints(points)
                        .counts(new Result<>(counts,
                                countsV09.moreAvailable() || countsPostV09.moreAvailable()))
                        .build();
            }
        }
    }

    private long splitCountIfNeeded(String agentRollupId, TraceQuery query,
            DelegateCountAction action) throws Exception {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        long count = 0;
        if (queryV09 != null) {
            count += action.count(V09Support.convertToV09(agentRollupId), queryV09);
        }
        TraceQuery queryPostV09 = plan.queryPostV09();
        if (queryPostV09 != null) {
            count += action.count(agentRollupId, queryPostV09);
        }
        return count;
    }

    private TraceQueryPlan getPlan(String agentRollupId, TraceQuery query) {
        if (query.from() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            if (query.to() <= v09LastCaptureTime) {
                return ImmutableTraceQueryPlan.builder()
                        .queryV09(query)
                        .build();
            } else {
                return ImmutableTraceQueryPlan.builder()
                        .queryV09(ImmutableTraceQuery.copyOf(query)
                                .withTo(v09LastCaptureTime))
                        .queryPostV09(ImmutableTraceQuery.copyOf(query)
                                .withFrom(v09LastCaptureTime + 1))
                        .build();
            }
        } else {
            return ImmutableTraceQueryPlan.builder()
                    .queryPostV09(query)
                    .build();
        }
    }

    @Value.Immutable
    interface TraceQueryPlan {
        @Nullable
        TraceQuery queryV09();
        @Nullable
        TraceQuery queryPostV09();
    }

    private interface DelegateCountAction {
        long count(String agentRollupId, TraceQuery query) throws Exception;
    }

    private interface DelegateResultAction {
        Result<TracePoint> result(String agentRollupId, TraceQuery query) throws Exception;
    }

    private interface DelegateErrorMessageResultAction {
        ErrorMessageResult result(String agentRollupId, TraceQuery query) throws Exception;
    }

    private static class MutableLong {
        private long value;
        private void add(long v) {
            value += v;
        }
    }
}
