/*
 * Copyright 2017-2023 the original author or authors.
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

import com.google.common.collect.Ordering;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.TraceDaoImpl;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.*;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableErrorMessageCount;
import org.glowroot.common2.repo.ImmutableErrorMessageResult;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.immutables.value.Value;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

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
        convertToPostV09AgentIds = new HashMap<>();
        for (String agentRollupIdWithV09Data : agentRollupIdsWithV09Data) {
            if (!agentRollupIdWithV09Data.endsWith("::")) {
                convertToPostV09AgentIds.put(V09Support.convertToV09(agentRollupIdWithV09Data),
                        agentRollupIdWithV09Data);
            }
        }
    }

    @CheckReturnValue
    @Override
    public CompletionStage<?> store(String agentId, Trace trace) {
        if (trace.getHeader().getCaptureTime() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentId)) {
            return delegate.store(V09Support.convertToV09(agentId),
                    V09Support.getAgentRollupIdsV09(agentId),
                    AgentRollupIds.getAgentRollupIds(agentId), trace);
        }
        return delegate.store(agentId, trace);
    }

    @Override
    public CompletionStage<Long> readSlowCount(String agentRollupId, TraceQuery query) {
        return splitCountIfNeeded(agentRollupId, query, (id, q) -> delegate.readSlowCount(id, q));
    }

    @Override
    public CompletionStage<Result<TracePoint>> readSlowPoints(String agentRollupId, TraceQuery query,
                                                              TracePointFilter filter, int limit) {
        return splitResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readSlowPoints(id, q, filter, limit));
    }

    @Override
    public CompletionStage<Long> readErrorCount(String agentRollupId, TraceQuery query) {
        return splitCountIfNeeded(agentRollupId, query, (id, q) -> delegate.readErrorCount(id, q));
    }

    @Override
    public CompletionStage<Result<TracePoint>> readErrorPoints(String agentRollupId, TraceQuery query,
                                                               TracePointFilter filter, int limit) throws Exception {
        return splitResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readErrorPoints(id, q, filter, limit));
    }

    @Override
    public CompletionStage<ErrorMessageResult> readErrorMessages(String agentRollupId, TraceQuery query,
                                                                 ErrorMessageFilter filter, long resolutionMillis, int limit) {
        return splitErrorMessageResultIfNeeded(agentRollupId, query, limit,
                (id, q) -> delegate.readErrorMessages(id, q, filter, resolutionMillis, limit));
    }

    @Override
    public CompletionStage<Long> readErrorMessageCount(String agentRollupId, TraceQuery query,
                                                       String errorMessageFilter, CassandraProfile profile) {
        return splitCountIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readErrorMessageCount(id, q, errorMessageFilter, profile));
    }

    @Override
    public CompletionStage<HeaderPlus> readHeaderPlus(String agentId, String traceId) {
        return delegate.readHeaderPlus(agentId, traceId).thenCompose(headerPlus -> {
            if (headerPlus != null) {
                return CompletableFuture.completedFuture(headerPlus);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (checkV09) {
                    return delegate.readHeaderPlus(V09Support.convertToV09(agentId), traceId);
                }
                return CompletableFuture.completedFuture(headerPlus);
            });
        });
    }

    @Override
    public CompletionStage<Entries> readEntries(String agentId, String traceId, CassandraProfile profile) {
        return delegate.readEntries(agentId, traceId, profile).thenCompose(entries -> {
            if (!entries.entries().isEmpty()) {
                return CompletableFuture.completedFuture(entries);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (checkV09) {
                    return delegate.readEntries(V09Support.convertToV09(agentId), traceId, profile);
                }
                return CompletableFuture.completedFuture(entries);
            });
        });
    }

    @Override
    public CompletionStage<Queries> readQueries(String agentId, String traceId, CassandraProfile profile) {
        return delegate.readQueries(agentId, traceId, profile).thenCompose(queries -> {
            if (!queries.queries().isEmpty()) {
                return CompletableFuture.completedFuture(queries);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (checkV09) {
                    return delegate.readQueries(V09Support.convertToV09(agentId), traceId, profile);
                }
                return CompletableFuture.completedFuture(queries);
            });
        });
    }

    @Override
    public CompletionStage<EntriesAndQueries> readEntriesAndQueriesForExport(String agentId, String traceId, CassandraProfile profile) {
        return delegate.readEntriesAndQueriesForExport(agentId, traceId, profile).thenCompose(entriesAndQueries -> {
            if (!entriesAndQueries.entries().isEmpty()) {
                return CompletableFuture.completedFuture(entriesAndQueries);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (clock.currentTimeMillis() < v09FqtLastExpirationTime
                        && checkV09) {
                    return delegate.readEntriesAndQueriesForExport(V09Support.convertToV09(agentId), traceId, profile);
                }
                return CompletableFuture.completedFuture(entriesAndQueries);
            });
        });
    }

    @Override
    public CompletionStage<Profile> readMainThreadProfile(String agentId, String traceId) {
        return delegate.readMainThreadProfile(agentId, traceId).thenCompose(profile -> {
            if (profile != null) {
                return CompletableFuture.completedFuture(profile);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (checkV09) {
                    return delegate.readMainThreadProfile(V09Support.convertToV09(agentId), traceId);
                }
                return CompletableFuture.completedFuture(profile);
            });
        });
    }

    @Override
    public CompletionStage<Profile> readAuxThreadProfile(String agentId, String traceId) {
        return delegate.readAuxThreadProfile(agentId, traceId).thenCompose(profile -> {
            if (profile != null) {
                return CompletableFuture.completedFuture(profile);
            }
            return checkV09(agentId, traceId).thenCompose(checkV09 -> {
                if (checkV09) {
                    return delegate.readAuxThreadProfile(V09Support.convertToV09(agentId), traceId);
                }
                return CompletableFuture.completedFuture(profile);
            });
        });
    }

    private CompletionStage<Boolean> checkV09(String agentId, String traceId) {
        if (!agentRollupIdsWithV09Data.contains(agentId)) {
            return CompletableFuture.completedFuture(false);
        }
        return delegate.readHeaderPlus(V09Support.convertToV09(agentId), traceId).thenApply(headerPlusV09 -> {
            if (headerPlusV09 == null) {
                return false;
            }
            return headerPlusV09.header().getCaptureTime() <= v09LastCaptureTime;
        });
    }

    private CompletionStage<Result<TracePoint>> splitResultIfNeeded(String agentRollupId, TraceQuery query,
                                                                    int limit, DelegateResultAction action) {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        TraceQuery queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return action.result(agentRollupId, queryPostV09);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return action.result(V09Support.convertToV09(agentRollupId), queryV09).thenApply(this::convertFromV09);
        } else {
            return action.result(V09Support.convertToV09(agentRollupId), queryV09).thenApply(this::convertFromV09)
                    .thenCombine(action.result(agentRollupId, queryPostV09), (resultV09, resultPostV09) -> {
                        List<TracePoint> tracePoints = new ArrayList<>();
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
                    });
        }
    }

    private Result<TracePoint> convertFromV09(Result<TracePoint> resultV09) {
        List<TracePoint> tracePoints = new ArrayList<>();
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

    private CompletionStage<ErrorMessageResult> splitErrorMessageResultIfNeeded(String agentRollupId,
                                                                                TraceQuery query, int limit, DelegateErrorMessageResultAction action) {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        TraceQuery queryPostV09 = plan.queryPostV09();
        return CompletableFuture.completedFuture(null).thenCompose(ignored -> {
            if (queryV09 == null) {
                checkNotNull(queryPostV09);
                return action.result(agentRollupId, queryPostV09);
            } else if (queryPostV09 == null) {
                checkNotNull(queryV09);
                return action.result(V09Support.convertToV09(agentRollupId), queryV09);
            } else {
                return action.result(V09Support.convertToV09(agentRollupId), queryV09).thenCompose(resultV09 -> {

                    return action.result(agentRollupId, queryPostV09).thenApply(resultPostV09 -> {
                        List<ErrorMessagePoint> points = new ArrayList<>();
                        points.addAll(resultV09.points());
                        points.addAll(resultPostV09.points());
                        Map<String, MutableLong> messageCounts = new HashMap<>();
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

                    });
                });
            }
        });
    }

    private CompletionStage<Long> splitCountIfNeeded(String agentRollupId, TraceQuery query,
                                                     DelegateCountAction action) {
        TraceQueryPlan plan = getPlan(agentRollupId, query);
        TraceQuery queryV09 = plan.queryV09();
        CompletionStage<Long> countQueryV09 = CompletableFuture.completedFuture(0L);
        if (queryV09 != null) {
            countQueryV09 = action.count(V09Support.convertToV09(agentRollupId), queryV09);
        }
        TraceQuery queryPostV09 = plan.queryPostV09();
        CompletionStage<Long> countQueryPostV09 = CompletableFuture.completedFuture(0L);
        if (queryPostV09 != null) {
            countQueryPostV09 = action.count(agentRollupId, queryPostV09);
        }
        return countQueryV09.thenCombine(countQueryPostV09, Long::sum);
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

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        delegate.truncateAll();
    }

    @Value.Immutable
    interface TraceQueryPlan {
        @Nullable
        TraceQuery queryV09();

        @Nullable
        TraceQuery queryPostV09();
    }

    private interface DelegateCountAction {
        CompletionStage<Long> count(String agentRollupId, TraceQuery query);
    }

    private interface DelegateResultAction {
        CompletionStage<Result<TracePoint>> result(String agentRollupId, TraceQuery query);
    }

    private interface DelegateErrorMessageResultAction {
        CompletionStage<ErrorMessageResult> result(String agentRollupId, TraceQuery query);
    }

    private static class MutableLong {
        private long value;

        private void add(long v) {
            value += v;
        }
    }
}
