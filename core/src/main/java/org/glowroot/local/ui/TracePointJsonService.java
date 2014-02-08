/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.MonotonicNonNull;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.local.store.TracePoint;
import org.glowroot.local.store.TracePointQuery;
import org.glowroot.local.store.TracePointQuery.StringComparator;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.TraceRegistry;
import org.glowroot.trace.model.Trace;

import static org.glowroot.common.Nullness.castNonNull;

/**
 * Json service to read trace point data, bound under /backend/trace/points.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class TracePointJsonService {

    private static final Logger logger = LoggerFactory.getLogger(TracePointJsonService.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final int NANOSECONDS_PER_SECOND = 1000000000;

    private final SnapshotDao snapshotDao;
    private final TraceRegistry traceRegistry;
    private final TraceCollectorImpl traceCollector;
    private final Ticker ticker;
    private final Clock clock;

    TracePointJsonService(SnapshotDao snapshotDao, TraceRegistry traceRegistry,
            TraceCollectorImpl traceCollector, Ticker ticker, Clock clock) {
        this.snapshotDao = snapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceCollector = traceCollector;
        this.ticker = ticker;
        this.clock = clock;
    }

    @GET("/backend/trace/points")
    String getPoints(String content) throws IOException {
        logger.debug("getPoints(): content={}", content);
        TracePointRequest request =
                ObjectMappers.readRequiredValue(mapper, content, TracePointRequest.class);
        return new Handler(request).handle();
    }

    private class Handler {

        private final TracePointRequest request;
        private long requestAt;
        private long low;
        private long high;
        @MonotonicNonNull
        private StringComparator groupingComparator;
        @MonotonicNonNull
        private StringComparator errorComparator;
        @MonotonicNonNull
        private StringComparator userComparator;

        public Handler(TracePointRequest request) {
            this.request = request;
        }

        private String handle() throws IOException {
            requestAt = clock.currentTimeMillis();
            if (request.getFrom() < 0) {
                request.setFrom(requestAt + request.getFrom());
            }
            low = (long) Math.ceil(request.getLow() * NANOSECONDS_PER_SECOND);
            high = request.getHigh() == 0 ? Long.MAX_VALUE : (long) Math.floor(request.getHigh()
                    * NANOSECONDS_PER_SECOND);
            String groupingComparator = request.getGroupingComparator();
            if (groupingComparator != null) {
                this.groupingComparator =
                        StringComparator.valueOf(groupingComparator.toUpperCase(Locale.ENGLISH));
            }
            String errorComparator = request.getErrorComparator();
            if (errorComparator != null) {
                this.errorComparator =
                        StringComparator.valueOf(errorComparator.toUpperCase(Locale.ENGLISH));
            }
            String userComparator = request.getUserComparator();
            if (userComparator != null) {
                this.userComparator =
                        StringComparator.valueOf(userComparator.toUpperCase(Locale.ENGLISH));
            }
            boolean captureActiveTraces = shouldCaptureActiveTraces();
            List<Trace> activeTraces = Lists.newArrayList();
            long captureTime = 0;
            long captureTick = 0;
            if (captureActiveTraces) {
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                activeTraces = getMatchingActiveTraces();
                // take capture timings after the capture to make sure there are no traces captured
                // that start after the recorded capture time (resulting in negative duration)
                captureTime = clock.currentTimeMillis();
                captureTick = ticker.read();
            }
            if (request.getTo() == 0) {
                request.setTo(requestAt);
            }
            List<TracePoint> points = getStoredAndPendingPoints(captureActiveTraces);
            removeDuplicatesBetweenActiveTracesAndPoints(activeTraces, points);
            boolean limitExceeded = (points.size() + activeTraces.size() > request.getLimit());
            if (points.size() + activeTraces.size() > request.getLimit()) {
                // points is already ordered, so just drop the last few items
                // always include all active traces
                points = points.subList(0, request.getLimit() - activeTraces.size());
            }
            return writeResponse(points, activeTraces, captureTime, captureTick, limitExceeded);
        }

        private boolean shouldCaptureActiveTraces() {
            return (request.getTo() == 0 || request.getTo() > requestAt)
                    && request.getFrom() < requestAt;
        }

        private List<TracePoint> getStoredAndPendingPoints(boolean captureActiveTraces) {
            List<TracePoint> matchingPendingPoints;
            // it only seems worth looking at pending traces if request asks for active traces
            if (captureActiveTraces) {
                // important to grab pending traces before stored points to ensure none are
                // missed in the transition between pending and stored
                matchingPendingPoints = getMatchingPendingPoints();
            } else {
                matchingPendingPoints = ImmutableList.of();
            }
            TracePointQuery query = new TracePointQuery(request.getFrom(), request.getTo(), low,
                    high, request.isBackground(), request.isErrorOnly(), request.isFineOnly(),
                    groupingComparator, request.getGrouping(), errorComparator, request.getError(),
                    userComparator, request.getUser(), request.getLimit() + 1);
            List<TracePoint> points = snapshotDao.readPoints(query);
            // create single merged and limited list of points
            List<TracePoint> orderedPoints = Lists.newArrayList(points);
            for (TracePoint pendingPoint : matchingPendingPoints) {
                insertIntoOrderedPoints(pendingPoint, orderedPoints);
            }
            return orderedPoints;
        }

        private List<Trace> getMatchingActiveTraces() {
            List<Trace> activeTraces = Lists.newArrayList();
            for (Trace trace : traceRegistry.getTraces()) {
                if (traceCollector.shouldStore(trace)
                        && matchesDuration(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesGrouping(trace)
                        && matchesUser(trace)
                        && matchesBackground(trace)) {
                    activeTraces.add(trace);
                }
            }
            Collections.sort(activeTraces,
                    Ordering.natural().onResultOf(new Function<Trace, Long>() {
                        @Override
                        public Long apply(@Nullable Trace trace) {
                            // sorting activeTraces which is List<@NonNull Trace>
                            castNonNull(trace);
                            return trace.getStartTick();
                        }
                    }));
            if (activeTraces.size() > request.getLimit()) {
                activeTraces = activeTraces.subList(0, request.getLimit());
            }
            return activeTraces;
        }

        private List<TracePoint> getMatchingPendingPoints() {
            List<TracePoint> points = Lists.newArrayList();
            for (Trace trace : traceCollector.getPendingCompleteTraces()) {
                if (matchesDuration(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesGrouping(trace)
                        && matchesUser(trace)
                        && matchesBackground(trace)) {
                    points.add(TracePoint.from(trace.getId(), clock.currentTimeMillis(),
                            trace.getDuration(), trace.getError() != null));
                }
            }
            return points;
        }

        private boolean matchesDuration(Trace trace) {
            long duration = trace.getDuration();
            return duration >= low && duration <= high;
        }

        private boolean matchesErrorOnly(Trace trace) {
            return !request.isErrorOnly() || trace.getError() != null;
        }

        private boolean matchesFineOnly(Trace trace) {
            return !request.isFineOnly() || trace.isFine();
        }

        private boolean matchesGrouping(Trace trace) {
            String grouping = request.getGrouping();
            if (groupingComparator == null || grouping == null) {
                return true;
            }
            String traceGrouping = trace.getGrouping();
            switch (groupingComparator) {
                case BEGINS:
                    return traceGrouping.toUpperCase(Locale.ENGLISH)
                            .startsWith(grouping.toUpperCase(Locale.ENGLISH));
                case EQUALS:
                    return traceGrouping.equalsIgnoreCase(grouping);
                case ENDS:
                    return traceGrouping.toUpperCase(Locale.ENGLISH)
                            .endsWith(grouping.toUpperCase(Locale.ENGLISH));
                case CONTAINS:
                    return traceGrouping.toUpperCase(Locale.ENGLISH)
                            .contains(grouping.toUpperCase(Locale.ENGLISH));
                case NOT_CONTAINS:
                    return !traceGrouping.toUpperCase(Locale.ENGLISH)
                            .contains(grouping.toUpperCase(Locale.ENGLISH));
                default:
                    throw new AssertionError("Unknown StringComparator enum: "
                            + groupingComparator);
            }
        }

        private boolean matchesUser(Trace trace) {
            String user = request.getUser();
            if (userComparator == null || user == null) {
                return true;
            }
            String traceUser = trace.getUser();
            if (traceUser == null) {
                return false;
            }
            switch (userComparator) {
                case BEGINS:
                    return traceUser.toUpperCase(Locale.ENGLISH)
                            .startsWith(user.toUpperCase(Locale.ENGLISH));
                case EQUALS:
                    return traceUser.equalsIgnoreCase(user);
                case ENDS:
                    return traceUser.toUpperCase(Locale.ENGLISH)
                            .endsWith(user.toUpperCase(Locale.ENGLISH));
                case CONTAINS:
                    return traceUser.toUpperCase(Locale.ENGLISH)
                            .contains(user.toUpperCase(Locale.ENGLISH));
                default:
                    throw new AssertionError("Unknown StringComparator enum: " + userComparator);
            }
        }

        private boolean matchesBackground(Trace trace) {
            Boolean background = request.isBackground();
            return background == null || background == trace.isBackground();
        }

        private void insertIntoOrderedPoints(TracePoint pendingPoint,
                List<TracePoint> orderedPoints) {
            int duplicateIndex = -1;
            int insertionIndex = -1;
            // check if duplicate and capture insertion index at the same time
            for (int i = 0; i < orderedPoints.size(); i++) {
                TracePoint point = orderedPoints.get(i);
                if (pendingPoint.getId().equals(point.getId())) {
                    duplicateIndex = i;
                    break;
                }
                if (pendingPoint.getDuration() > point.getDuration()) {
                    insertionIndex = i;
                    break;
                }
            }
            if (duplicateIndex != -1) {
                TracePoint point = orderedPoints.get(duplicateIndex);
                if (pendingPoint.getDuration() > point.getDuration()) {
                    // prefer the pending trace, it must be a stuck trace that has just completed
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

        private void removeDuplicatesBetweenActiveTracesAndPoints(List<Trace> activeTraces,
                List<TracePoint> points) {
            for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
                Trace activeTrace = i.next();
                for (Iterator<TracePoint> j = points.iterator(); j.hasNext();) {
                    TracePoint point = j.next();
                    if (activeTrace.getId().equals(point.getId())) {
                        if (activeTrace.getDuration() > point.getDuration()) {
                            // prefer the active trace, it must be a stuck trace that hasn't
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
        }

        private String writeResponse(@ReadOnly List<TracePoint> points,
                @ReadOnly List<Trace> activeTraces, long captureTime, long captureTick,
                boolean limitExceeded) throws IOException {
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeArrayFieldStart("normalPoints");
            for (TracePoint point : points) {
                if (!point.isError()) {
                    jg.writeStartArray();
                    jg.writeNumber(point.getCaptureTime());
                    jg.writeNumber(point.getDuration() / 1000000000.0);
                    jg.writeString(point.getId());
                    jg.writeEndArray();
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("errorPoints");
            for (TracePoint point : points) {
                if (point.isError()) {
                    jg.writeStartArray();
                    jg.writeNumber(point.getCaptureTime());
                    jg.writeNumber(point.getDuration() / 1000000000.0);
                    jg.writeString(point.getId());
                    jg.writeEndArray();
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("activePoints");
            for (Trace activeTrace : activeTraces) {
                jg.writeStartArray();
                jg.writeNumber(captureTime);
                jg.writeNumber((captureTick - activeTrace.getStartTick()) / 1000000000.0);
                jg.writeString(activeTrace.getId());
                jg.writeEndArray();
            }
            jg.writeEndArray();
            if (limitExceeded) {
                jg.writeBooleanField("limitExceeded", true);
            }
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }
}
