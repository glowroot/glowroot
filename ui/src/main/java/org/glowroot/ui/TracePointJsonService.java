/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.util.Clock;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableTraceQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TraceRepository.TraceQuery;

import static java.util.concurrent.TimeUnit.HOURS;

@JsonService
class TracePointJsonService {

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;
    private final ConfigRepository configRepository;
    private final Ticker ticker;
    private final Clock clock;

    TracePointJsonService(TraceRepository traceRepository, LiveTraceRepository liveTraceRepository,
            ConfigRepository configRepository, Ticker ticker, Clock clock) {
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
        this.configRepository = configRepository;
        this.ticker = ticker;
        this.clock = clock;
    }

    @GET("/backend/transaction/points")
    String getTransactionPoints(String queryString) throws Exception {
        return getPoints(TraceKind.SLOW, queryString);
    }

    @GET("/backend/error/points")
    String getErrorPoints(String queryString) throws Exception {
        return getPoints(TraceKind.ERROR, queryString);
    }

    private String getPoints(TraceKind traceKind, String queryString) throws Exception {
        TracePointRequest request = QueryStrings.decode(queryString, TracePointRequest.class);

        double durationMillisLow = request.responseTimeMillisLow();
        long durationNanosLow = Math.round(durationMillisLow * NANOSECONDS_PER_MILLISECOND);
        Long durationNanosHigh = null;
        Double durationMillisHigh = request.responseTimeMillisHigh();
        if (durationMillisHigh != null) {
            durationNanosHigh = Math.round(durationMillisHigh * NANOSECONDS_PER_MILLISECOND);
        }
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(request.serverRollup())
                .transactionType(request.transactionType())
                .transactionName(request.transactionName())
                .from(request.from())
                .to(request.to())
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(durationNanosLow)
                .durationNanosHigh(durationNanosHigh)
                .errorMessageComparator(request.errorMessageComparator())
                .errorMessage(request.errorMessage())
                .userComparator(request.userComparator())
                .user(request.user())
                .attributeName(request.attributeName())
                .attributeValueComparator(request.attributeValueComparator())
                .attributeValue(request.attributeValue())
                .build();
        return new Handler(traceKind, query, filter, request.limit()).handle();
    }

    private class Handler {

        private final TraceKind traceKind;
        private final TraceQuery query;
        private final TracePointFilter filter;
        private final int limit;

        private Handler(TraceKind traceKind, TraceQuery query, TracePointFilter filter, int limit) {
            this.traceKind = traceKind;
            this.query = query;
            this.filter = filter;
            this.limit = limit;
        }

        private String handle() throws Exception {
            boolean captureActiveTracePoints = shouldCaptureActiveTracePoints();
            List<TracePoint> activeTracePoints = Lists.newArrayList();
            long captureTime = 0;
            long captureTick = 0;
            if (captureActiveTracePoints) {
                captureTime = clock.currentTimeMillis();
                captureTick = ticker.read();
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                activeTracePoints.addAll(liveTraceRepository.getMatchingActiveTracePoints(traceKind,
                        query.serverRollup(), query.transactionType(), query.transactionName(),
                        filter, limit, captureTime, captureTick));
            }
            Result<TracePoint> queryResult =
                    getStoredAndPendingPoints(captureTime, captureActiveTracePoints);
            List<TracePoint> points = Lists.newArrayList(queryResult.records());
            removeDuplicatesBetweenActiveAndNormalTracePoints(activeTracePoints, points);
            boolean expired = points.isEmpty() && query.to() < clock.currentTimeMillis()
                    - HOURS.toMillis(configRepository.getStorageConfig().traceExpirationHours());
            List<String> traceAttributeNames =
                    traceRepository.readTraceAttributeNames(query.serverRollup());
            return writeResponse(points, activeTracePoints, queryResult.moreAvailable(), expired,
                    traceAttributeNames);
        }

        private boolean shouldCaptureActiveTracePoints() {
            long currentTimeMillis = clock.currentTimeMillis();
            return (query.to() == 0 || query.to() > currentTimeMillis)
                    && query.from() < currentTimeMillis;
        }

        private Result<TracePoint> getStoredAndPendingPoints(long captureTime,
                boolean captureActiveTraces) throws Exception {
            List<TracePoint> matchingPendingPoints;
            // it only seems worth looking at pending traces if request asks for active traces
            if (captureActiveTraces) {
                // important to grab pending traces before stored points to ensure none are
                // missed in the transition between pending and stored
                matchingPendingPoints = liveTraceRepository.getMatchingPendingPoints(traceKind,
                        query.serverRollup(), query.transactionType(), query.transactionName(),
                        filter, captureTime);
            } else {
                matchingPendingPoints = ImmutableList.of();
            }
            Result<TracePoint> queryResult;
            if (traceKind == TraceKind.SLOW) {
                queryResult = traceRepository.readSlowPoints(query, filter, limit);
            } else {
                // TraceKind.ERROR
                queryResult = traceRepository.readErrorPoints(query, filter, limit);
            }
            // create single merged and limited list of points
            List<TracePoint> orderedPoints = Lists.newArrayList(queryResult.records());
            for (TracePoint pendingPoint : matchingPendingPoints) {
                insertIntoOrderedPoints(pendingPoint, orderedPoints);
            }
            return new Result<TracePoint>(orderedPoints, queryResult.moreAvailable());
        }

        private void insertIntoOrderedPoints(TracePoint pendingPoint,
                List<TracePoint> orderedPoints) {
            int duplicateIndex = -1;
            int insertionIndex = -1;
            // check if duplicate and capture insertion index at the same time
            for (int i = 0; i < orderedPoints.size(); i++) {
                TracePoint point = orderedPoints.get(i);
                if (pendingPoint.traceId().equals(point.traceId())) {
                    duplicateIndex = i;
                    break;
                }
                if (pendingPoint.durationNanos() > point.durationNanos()) {
                    insertionIndex = i;
                    break;
                }
            }
            if (duplicateIndex != -1) {
                TracePoint point = orderedPoints.get(duplicateIndex);
                if (pendingPoint.durationNanos() > point.durationNanos()) {
                    // prefer the pending trace, it must be a partial trace that has just completed
                    orderedPoints.set(duplicateIndex, pendingPoint);
                }
                return;
            }
            if (insertionIndex == -1) {
                orderedPoints.add(pendingPoint);
            } else {
                orderedPoints.add(insertionIndex, pendingPoint);
            }
        }

        private void removeDuplicatesBetweenActiveAndNormalTracePoints(
                List<TracePoint> activeTracePoints, List<TracePoint> points) {
            for (Iterator<TracePoint> i = activeTracePoints.iterator(); i.hasNext();) {
                TracePoint activeTracePoint = i.next();
                for (Iterator<TracePoint> j = points.iterator(); j.hasNext();) {
                    TracePoint point = j.next();
                    if (!activeTracePoint.traceId().equals(point.traceId())) {
                        continue;
                    }
                    if (activeTracePoint.durationNanos() > point.durationNanos()) {
                        // prefer the active trace, it must be a partial trace that hasn't
                        // completed yet
                        j.remove();
                    } else {
                        // otherwise prefer the completed trace
                        i.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }

        private String writeResponse(List<TracePoint> points, List<TracePoint> activePoints,
                boolean limitExceeded, boolean expired, List<String> traceAttributeNames)
                        throws Exception {
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeArrayFieldStart("normalPoints");
            for (TracePoint point : points) {
                if (!point.error()) {
                    writePoint(point, jg);
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("errorPoints");
            for (TracePoint point : points) {
                if (point.error()) {
                    writePoint(point, jg);
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("activePoints");
            for (TracePoint activePoint : activePoints) {
                writePoint(activePoint, jg);
            }
            jg.writeEndArray();
            if (limitExceeded) {
                jg.writeBooleanField("limitExceeded", true);
            }
            if (expired) {
                jg.writeBooleanField("expired", true);
            }
            jg.writeArrayFieldStart("traceAttributeNames");
            for (String traceAttributeName : traceAttributeNames) {
                jg.writeString(traceAttributeName);
            }
            jg.writeEndArray();
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }

        private void writePoint(TracePoint point, JsonGenerator jg) throws IOException {
            jg.writeStartArray();
            jg.writeNumber(point.captureTime());
            jg.writeNumber(point.durationNanos() / NANOSECONDS_PER_MILLISECOND);
            jg.writeString(point.serverId());
            jg.writeString(point.traceId());
            jg.writeEndArray();
        }
    }

    // same as TracePointQuery but with milliseconds instead of nanoseconds
    @Value.Immutable
    public abstract static class TracePointRequest {

        public abstract String serverRollup();
        public abstract String transactionType();
        public abstract @Nullable String transactionName();
        public abstract long from();
        public abstract long to();
        public abstract double responseTimeMillisLow();
        public abstract @Nullable Double responseTimeMillisHigh();
        public abstract @Nullable StringComparator errorMessageComparator();
        public abstract @Nullable String errorMessage();
        public abstract @Nullable StringComparator userComparator();
        public abstract @Nullable String user();
        public abstract @Nullable String attributeName();
        public abstract @Nullable StringComparator attributeValueComparator();
        public abstract @Nullable String attributeValue();

        public abstract int limit();
    }
}
