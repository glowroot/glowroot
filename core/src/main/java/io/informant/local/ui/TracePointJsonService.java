/**
 * Copyright 2011-2012 the original author or authors.
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

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceRegistry;
import io.informant.core.util.Clock;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshotDao;
import io.informant.local.trace.TraceSnapshotDao.StringComparator;
import io.informant.local.trace.TraceSnapshotPoint;
import io.informant.local.trace.TraceSnapshotService;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TracePointJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TracePointJsonService.class);

    private static final int NANOSECONDS_PER_MILLISECOND = 1000000;

    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceRegistry traceRegistry;
    private final TraceSinkLocal traceSinkLocal;
    private final TraceSnapshotService traceSnapshotService;
    private final Ticker ticker;
    private final Clock clock;
    private final Gson gson = new Gson();

    @Inject
    TracePointJsonService(TraceSnapshotDao traceSnapshotDao, TraceRegistry traceRegistry,
            TraceSinkLocal traceSinkLocal, TraceSnapshotService traceSnapshotService,
            Ticker ticker, Clock clock) {

        this.traceSnapshotDao = traceSnapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceSinkLocal = traceSinkLocal;
        this.traceSnapshotService = traceSnapshotService;
        this.ticker = ticker;
        this.clock = clock;
    }

    @JsonServiceMethod
    String getPoints(String message) throws IOException {
        return new Handler().handle(message);
    }

    private class Handler {

        private TraceRequest request;
        private long requestAt;
        private long low;
        private long high;
        @Nullable
        private StringComparator headlineComparator;
        @Nullable
        private StringComparator userIdComparator;
        private List<Trace> activeTraces = ImmutableList.of();
        private long capturedAt;
        private long captureTick;
        private List<TraceSnapshotPoint> points;

        private String handle(String message) throws IOException {
            logger.debug("getPoints(): message={}", message);
            try {
                request = gson.fromJson(message, TraceRequest.class);
            } catch (JsonSyntaxException e) {
                logger.warn(e.getMessage(), e);
                return writeResponse(ImmutableList.<TraceSnapshotPoint> of(),
                        ImmutableList.<Trace> of(), 0, 0, false);
            }
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
            if (captureActiveTraces) {
                // capture active traces first to make sure that none are missed in the transition
                // between active and pending/stored (possible duplicates are removed below)
                getMatchingActiveTraces();
            }
            if (request.getTo() == 0) {
                request.setTo(requestAt);
            }
            points = getStoredAndPendingPoints(captureActiveTraces);
            removeDuplicatesBetweenActiveTracesAndPoints();
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

        private List<TraceSnapshotPoint> getStoredAndPendingPoints(boolean captureActiveTraces) {
            List<TraceSnapshotPoint> matchingPendingPoints;
            // it only seems worth looking at pending traces if request asks for active traces
            if (captureActiveTraces) {
                // important to grab pending traces before stored points to ensure none are
                // missed in the transition between pending and stored
                matchingPendingPoints = getMatchingPendingPoints();
            } else {
                matchingPendingPoints = ImmutableList.of();
            }
            List<TraceSnapshotPoint> points = traceSnapshotDao.readPoints(request.getFrom(),
                    request.getTo(), low, high, request.isBackground(), request.isErrorOnly(),
                    request.isFineOnly(), headlineComparator, request.getHeadline(),
                    userIdComparator, request.getUserId(), request.getLimit() + 1);
            if (!matchingPendingPoints.isEmpty()) {
                // create single merged and limited list of points
                List<TraceSnapshotPoint> combinedPoints = Lists.newArrayList(points);
                for (TraceSnapshotPoint pendingPoint : matchingPendingPoints) {
                    mergeIntoCombinedPoints(pendingPoint, combinedPoints);
                }
                return combinedPoints;
            } else {
                return points;
            }
        }

        private void getMatchingActiveTraces() {
            activeTraces = Lists.newArrayList();
            for (Trace trace : traceRegistry.getTraces()) {
                if (traceSnapshotService.shouldStore(trace)
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
                            return trace.getStartTick();
                        }
                    }));
            if (activeTraces.size() > request.getLimit()) {
                activeTraces = activeTraces.subList(0, request.getLimit());
            }
            // take capture timings after the capture to make sure there are no traces captured
            // that start after the recorded capture time (resulting in negative duration)
            capturedAt = clock.currentTimeMillis();
            captureTick = ticker.read();
        }

        private List<TraceSnapshotPoint> getMatchingPendingPoints() {
            List<TraceSnapshotPoint> points = Lists.newArrayList();
            for (Trace trace : traceSinkLocal.getPendingCompleteTraces()) {
                if (matchesDuration(trace)
                        && matchesErrorOnly(trace)
                        && matchesFineOnly(trace)
                        && matchesHeadline(trace)
                        && matchesUserId(trace)
                        && matchesBackground(trace)) {
                    points.add(TraceSnapshotPoint.from(trace.getId(), clock.currentTimeMillis(),
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
            if (headlineComparator == null || request.getHeadline() == null) {
                return true;
            }
            String traceHeadline = trace.getRootSpan().getMessageSupplier().get().getText();
            if (traceHeadline == null) {
                return false;
            }
            switch (headlineComparator) {
            case BEGINS:
                return traceHeadline.toUpperCase(Locale.ENGLISH)
                        .startsWith(request.getHeadline().toUpperCase(Locale.ENGLISH));
            case CONTAINS:
                return traceHeadline.toUpperCase(Locale.ENGLISH)
                        .contains(request.getHeadline().toUpperCase(Locale.ENGLISH));
            case EQUALS:
                return traceHeadline.equalsIgnoreCase(request.getHeadline());
            default:
                logger.error("unexpected user id comparator '{}'", userIdComparator);
                return false;
            }
        }

        private boolean matchesUserId(Trace trace) {
            if (userIdComparator == null || request.getUserId() == null) {
                return true;
            }
            String traceUserId = trace.getUserId();
            if (traceUserId == null) {
                return false;
            }
            switch (userIdComparator) {
            case BEGINS:
                return traceUserId.toUpperCase(Locale.ENGLISH)
                        .startsWith(request.getUserId().toUpperCase(Locale.ENGLISH));
            case CONTAINS:
                return traceUserId.toUpperCase(Locale.ENGLISH)
                        .contains(request.getUserId().toUpperCase(Locale.ENGLISH));
            case EQUALS:
                return traceUserId.equalsIgnoreCase(request.getUserId());
            default:
                logger.error("unexpected user id comparator '{}'", userIdComparator);
                return false;
            }
        }

        private boolean matchesBackground(Trace trace) {
            return request.isBackground() == null || request.isBackground() == trace.isBackground();
        }

        private void mergeIntoCombinedPoints(TraceSnapshotPoint pendingPoint,
                List<TraceSnapshotPoint> combinedPoints) {

            boolean duplicate = false;
            int orderedInsertionIndex = 0;
            // check if duplicate and capture ordered insertion index at the same time
            for (int i = 0; i < combinedPoints.size(); i++) {
                TraceSnapshotPoint point = combinedPoints.get(i);
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

        private void removeDuplicatesBetweenActiveTracesAndPoints() {
            for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
                Trace activeTrace = i.next();
                for (Iterator<TraceSnapshotPoint> j = points.iterator(); j.hasNext();) {
                    TraceSnapshotPoint point = j.next();
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

        private String writeResponse(List<TraceSnapshotPoint> points, List<Trace> activeTraces,
                long capturedAt, long captureTick, boolean limitExceeded) throws IOException {

            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("normalPoints").beginArray();
            for (TraceSnapshotPoint point : points) {
                if (!point.isError()) {
                    jw.beginArray();
                    jw.value(point.getCapturedAt());
                    jw.value(point.getDuration() / 1000000000.0);
                    jw.value(point.getId());
                    jw.endArray();
                }
            }
            jw.endArray();
            jw.name("errorPoints").beginArray();
            for (TraceSnapshotPoint point : points) {
                if (point.isError()) {
                    jw.beginArray();
                    jw.value(point.getCapturedAt());
                    jw.value(point.getDuration() / 1000000000.0);
                    jw.value(point.getId());
                    jw.endArray();
                }
            }
            jw.endArray();
            jw.name("activePoints").beginArray();
            for (Trace activeTrace : activeTraces) {
                jw.beginArray();
                jw.value(capturedAt);
                jw.value((captureTick - activeTrace.getStartTick()) / 1000000000.0);
                jw.value(activeTrace.getId());
                jw.endArray();
            }
            jw.endArray();
            if (limitExceeded) {
                jw.name("limitExceeded");
                jw.value(true);
            }
            jw.endObject();
            jw.close();
            return sw.toString();
        }
    }
}
