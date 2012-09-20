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
package org.informantproject.local.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.Clock;
import org.informantproject.local.trace.TraceSnapshotDao;
import org.informantproject.local.trace.TraceSnapshotDao.StringComparator;
import org.informantproject.local.trace.TraceSnapshotService;
import org.informantproject.local.trace.TraceSnapshotSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
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
    private final TraceSnapshotService traceSnapshotService;
    private final Ticker ticker;
    private final Clock clock;
    private final Gson gson = new Gson();

    @Inject
    TracePointJsonService(TraceSnapshotDao traceSnapshotDao, TraceRegistry traceRegistry,
            TraceSnapshotService traceSnapshotService, Ticker ticker, Clock clock) {

        this.traceSnapshotDao = traceSnapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceSnapshotService = traceSnapshotService;
        this.ticker = ticker;
        this.clock = clock;
    }

    @JsonServiceMethod
    String getPoints(String message) throws IOException {
        logger.debug("getChartPoints(): message={}", message);
        TraceRequest request;
        try {
            request = gson.fromJson(message, TraceRequest.class);
        } catch (JsonSyntaxException e) {
            logger.warn(e.getMessage(), e);
            return writeResponse(ImmutableList.<TraceSnapshotSummary> of(),
                    ImmutableList.<Trace> of(), 0, 0);
        }
        long requestAt = clock.currentTimeMillis();
        if (request.getFrom() < 0) {
            request.setFrom(requestAt + request.getFrom());
        }
        // since low and high are qualified using <= (instead of <), and precision in the database
        // is in whole nanoseconds, ceil(low) and floor(high) give the correct final result even in
        // cases where low and high are not in whole nanoseconds
        long low = (long) Math.ceil(request.getLow() * NANOSECONDS_PER_MILLISECOND);
        long high = request.getHigh() == 0 ? Long.MAX_VALUE : (long) Math.floor(request.getHigh()
                * NANOSECONDS_PER_MILLISECOND);
        StringComparator userIdComparator = null;
        String comparatorText = request.getUserIdComparator();
        if (comparatorText != null) {
            userIdComparator = StringComparator.valueOf(comparatorText
                    .toUpperCase(Locale.ENGLISH));
        }
        List<Trace> activeTraces = ImmutableList.of();
        long capturedAt = 0;
        long captureTick = 0;
        if ((request.getTo() == 0 || request.getTo() > requestAt)
                && request.getFrom() < requestAt) {
            // capture active traces first to make sure that none are missed in between reading
            // stored traces and then capturing active traces (possible duplicates are removed
            // below)
            activeTraces = getActiveTraces(low, high, request.isBackground(),
                    request.isErrorOnly(),
                    request.isFineOnly(), userIdComparator, request.getUserId());
            // take capture timings after the capture to make sure there no traces captured that
            // start after the recorded capture time (resulting in negative duration)
            capturedAt = clock.currentTimeMillis();
            captureTick = ticker.read();
        }
        if (request.getTo() == 0) {
            request.setTo(requestAt);
        }
        List<TraceSnapshotSummary> summaries = traceSnapshotDao.readSummaries(
                request.getFrom(), request.getTo(), low, high, request.isBackground(),
                request.isErrorOnly(), request.isFineOnly(), userIdComparator, request.getUserId());
        // remove duplicates between active and stored traces
        for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
            Trace activeTrace = i.next();
            for (Iterator<TraceSnapshotSummary> j = summaries.iterator(); j.hasNext();) {
                TraceSnapshotSummary summary = j.next();
                if (activeTrace.getId().equals(summary.getId())) {
                    // prefer stored trace if it is completed, otherwise prefer active trace
                    if (summary.isCompleted()) {
                        i.remove();
                    } else {
                        j.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }
        return writeResponse(summaries, activeTraces, capturedAt, captureTick);
    }

    private List<Trace> getActiveTraces(long low, long high, @Nullable Boolean background,
            boolean errorOnly, boolean fineOnly, @Nullable StringComparator userIdComparator,
            @Nullable String userId) {

        List<Trace> activeTraces = Lists.newArrayList();
        for (Trace trace : traceRegistry.getTraces()) {
            long duration = trace.getDuration();
            if (traceSnapshotService.shouldPersist(trace)
                    && matchesDuration(duration, low, high)
                    && matchesBackground(trace, background)
                    && matchesErrorOnly(trace, errorOnly)
                    && matchesFineOnly(trace, fineOnly)
                    && matchesUserId(trace, userIdComparator, userId)) {
                activeTraces.add(trace);
            } else {
                // the traces are ordered by start time so it's safe to break now
                break;
            }
        }
        return activeTraces;
    }

    private boolean matchesDuration(long duration, long low, long high) {
        return duration >= low && duration <= high;
    }

    private boolean matchesBackground(Trace trace, @Nullable Boolean background) {
        return background == null || background == trace.isBackground();
    }

    private boolean matchesErrorOnly(Trace trace, boolean errorOnly) {
        return !errorOnly || trace.isError();
    }

    private boolean matchesFineOnly(Trace trace, boolean fineOnly) {
        return !fineOnly || trace.isFine();
    }

    private boolean matchesUserId(Trace trace, @Nullable StringComparator userIdComparator,
            @Nullable String userId) {

        if (userIdComparator == null || userId == null) {
            return true;
        }
        String traceUserId = trace.getUserId();
        if (traceUserId == null) {
            return false;
        }
        switch (userIdComparator) {
        case BEGINS:
            return traceUserId.startsWith(userId);
        case CONTAINS:
            return traceUserId.contains(userId);
        case EQUALS:
            return traceUserId.equals(userId);
        default:
            logger.error("unexpected user id comparator '{}'", userIdComparator);
            return false;
        }
    }

    private static String writeResponse(List<TraceSnapshotSummary> summaries,
            List<Trace> activeTraces, long capturedAt, long captureTick) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("activePoints").beginArray();
        for (Trace activeTrace : activeTraces) {
            jw.beginArray();
            jw.value(capturedAt);
            jw.value((captureTick - activeTrace.getStartTick()) / 1000000000.0);
            jw.value(activeTrace.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.name("snapshotPoints").beginArray();
        for (TraceSnapshotSummary summary : summaries) {
            jw.beginArray();
            jw.value(summary.getCapturedAt());
            jw.value(summary.getDuration() / 1000000000.0);
            jw.value(summary.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
