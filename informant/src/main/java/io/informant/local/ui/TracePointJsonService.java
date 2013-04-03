/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.ui;

import static com.google.common.base.Preconditions.checkNotNull;

import io.informant.common.Clock;
import io.informant.common.ObjectMappers;
import io.informant.local.store.SnapshotDao;
import io.informant.local.store.SnapshotDao.StringComparator;
import io.informant.local.store.TracePoint;
import io.informant.markers.Singleton;
import io.informant.snapshot.SnapshotTraceSink;
import io.informant.trace.TraceRegistry;
import io.informant.trace.model.Trace;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TracePointJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TracePointJsonService.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final int NANOSECONDS_PER_MILLISECOND = 1000000;

    private final SnapshotDao snapshotDao;
    private final TraceRegistry traceRegistry;
    private final SnapshotTraceSink traceSink;
    private final Ticker ticker;
    private final Clock clock;

    TracePointJsonService(SnapshotDao snapshotDao, TraceRegistry traceRegistry,
            SnapshotTraceSink traceSink, Ticker ticker, Clock clock) {
        this.snapshotDao = snapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.ticker = ticker;
        this.clock = clock;
    }

    @JsonServiceMethod
    String getPoints(String message) throws IOException {
        logger.debug("getPoints(): message={}", message);
        TracePointRequest request =
                ObjectMappers.readRequiredValue(mapper, message, TracePointRequest.class);
        return new Handler(request).handle();
    }

    private class Handler {

        private final TracePointRequest request;
        private long requestAt;
        private long low;
        private long high;
        @LazyNonNull
        private StringComparator headlineComparator;
        @LazyNonNull
        private StringComparator userIdComparator;

        public Handler(TracePointRequest request) {
            this.request = request;
        }

        private String handle() throws IOException {
            requestAt = clock.currentTimeMillis();
            if (request.getFrom() < 0) {
                request.setFrom(requestAt + request.getFrom());
            }
            low = (long) Math.ceil(request.getLow() * NANOSECONDS_PER_MILLISECOND);
            high = request.getHigh() == 0 ? Long.MAX_VALUE : (long) Math.floor(request.getHigh()
                    * NANOSECONDS_PER_MILLISECOND);
            String headlineText = request.getHeadlineComparator();
            if (headlineText != null) {
                headlineComparator = StringComparator.valueOf(headlineText
                        .toUpperCase(Locale.ENGLISH));
            }
            String comparatorText = request.getUserIdComparator();
            if (comparatorText != null) {
                userIdComparator = StringComparator.valueOf(comparatorText
                        .toUpperCase(Locale.ENGLISH));
            }
            boolean captureActiveTraces = shouldCaptureActiveTraces();
            List<Trace> activeTraces = Lists.newArrayList();
            long capturedAt = 0;
            long captureTick = 0;
            if (captureActiveTraces) {
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                activeTraces = getMatchingActiveTraces();
                // take capture timings after the capture to make sure there are no traces captured
                // that start after the recorded capture time (resulting in negative duration)
                capturedAt = clock.currentTimeMillis();
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
            return writeResponse(points, activeTraces, capturedAt, captureTick, limitExceeded);
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
            List<TracePoint> points = snapshotDao.readPoints(request.getFrom(),
                    request.getTo(), low, high, request.isBackground(), request.isErrorOnly(),
                    request.isFineOnly(), headlineComparator, request.getHeadline(),
                    userIdComparator, request.getUserId(), request.getLimit() + 1);
            // create single merged and limited list of points
            List<TracePoint> combinedPoints = Lists.newArrayList(points);
            for (TracePoint pendingPoint : matchingPendingPoints) {
                mergeIntoCombinedPoints(pendingPoint, combinedPoints);
            }
            return combinedPoints;
        }

        private List<Trace> getMatchingActiveTraces() {
            List<Trace> activeTraces = Lists.newArrayList();
            for (Trace trace : traceRegistry.getTraces()) {
                if (traceSink.shouldStore(trace)
                        && matchesDuration(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesHeadline(trace)
                        && matchesUserId(trace)
                        && matchesBackground(trace)) {
                    activeTraces.add(trace);
                }
            }
            Collections.sort(activeTraces,
                    Ordering.natural().onResultOf(new Function<Trace, Long>() {
                        public Long apply(@Nullable Trace trace) {
                            checkNotNull(trace, "Ordering of non-null elements only");
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
            for (Trace trace : traceSink.getPendingCompleteTraces()) {
                if (matchesDuration(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesHeadline(trace)
                        && matchesUserId(trace)
                        && matchesBackground(trace)) {
                    points.add(TracePoint.from(trace.getId(), clock.currentTimeMillis(),
                            trace.getDuration(), true, trace.isError()));
                }
            }
            return points;
        }

        private boolean matchesDuration(Trace trace) {
            long duration = trace.getDuration();
            return duration >= low && duration <= high;
        }

        private boolean matchesErrorOnly(Trace trace) {
            return !request.isErrorOnly() || trace.isError();
        }

        private boolean matchesFineOnly(Trace trace) {
            return !request.isFineOnly() || trace.isFine();
        }

        private boolean matchesHeadline(Trace trace) {
            String headline = request.getHeadline();
            if (headlineComparator == null || headline == null) {
                return true;
            }
            String traceHeadline = trace.getHeadline();
            switch (headlineComparator) {
                case BEGINS:
                    return traceHeadline.toUpperCase(Locale.ENGLISH)
                            .startsWith(headline.toUpperCase(Locale.ENGLISH));
                case CONTAINS:
                    return traceHeadline.toUpperCase(Locale.ENGLISH)
                            .contains(headline.toUpperCase(Locale.ENGLISH));
                case EQUALS:
                    return traceHeadline.equalsIgnoreCase(headline);
                default:
                    throw new IllegalStateException("Unexpected headline comparator: "
                            + headlineComparator);
            }
        }

        private boolean matchesUserId(Trace trace) {
            String userId = request.getUserId();
            if (userIdComparator == null || userId == null) {
                return true;
            }
            String traceUserId = trace.getUserId();
            if (traceUserId == null) {
                return false;
            }
            switch (userIdComparator) {
                case BEGINS:
                    return traceUserId.toUpperCase(Locale.ENGLISH)
                            .startsWith(userId.toUpperCase(Locale.ENGLISH));
                case CONTAINS:
                    return traceUserId.toUpperCase(Locale.ENGLISH)
                            .contains(userId.toUpperCase(Locale.ENGLISH));
                case EQUALS:
                    return traceUserId.equalsIgnoreCase(userId);
                default:
                    throw new IllegalStateException("Unexpected user id comparator: "
                            + userIdComparator);
            }
        }

        private boolean matchesBackground(Trace trace) {
            Boolean background = request.isBackground();
            return background == null || background == trace.isBackground();
        }

        private void mergeIntoCombinedPoints(TracePoint pendingPoint,
                List<TracePoint> combinedPoints) {
            boolean duplicate = false;
            int orderedInsertionIndex = 0;
            // check if duplicate and capture ordered insertion index at the same time
            for (int i = 0; i < combinedPoints.size(); i++) {
                TracePoint point = combinedPoints.get(i);
                if (pendingPoint.getDuration() < point.getDuration()) {
                    // keep pushing orderedInsertionIndex down the line
                    orderedInsertionIndex = i + 1;
                }
                if (pendingPoint.getId().equals(point.getId())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                combinedPoints.add(orderedInsertionIndex, pendingPoint);
            }
        }

        private void removeDuplicatesBetweenActiveTracesAndPoints(List<Trace> activeTraces,
                List<TracePoint> points) {
            for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
                Trace activeTrace = i.next();
                for (Iterator<TracePoint> j = points.iterator(); j.hasNext();) {
                    TracePoint point = j.next();
                    if (activeTrace.getId().equals(point.getId())) {
                        // prefer stored trace if it is completed, otherwise prefer active trace
                        if (point.isCompleted()) {
                            i.remove();
                        } else {
                            j.remove();
                        }
                        // there can be at most one duplicate per id, so ok to break to outer
                        break;
                    }
                }
            }
        }

        private String writeResponse(@ReadOnly List<TracePoint> points,
                @ReadOnly List<Trace> activeTraces, long capturedAt, long captureTick,
                boolean limitExceeded) throws IOException {
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeArrayFieldStart("normalPoints");
            for (TracePoint point : points) {
                if (!point.isError()) {
                    jg.writeStartArray();
                    jg.writeNumber(point.getCapturedAt());
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
                    jg.writeNumber(point.getCapturedAt());
                    jg.writeNumber(point.getDuration() / 1000000000.0);
                    jg.writeString(point.getId());
                    jg.writeEndArray();
                }
            }
            jg.writeEndArray();
            jg.writeArrayFieldStart("activePoints");
            for (Trace activeTrace : activeTraces) {
                jg.writeStartArray();
                jg.writeNumber(capturedAt);
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
