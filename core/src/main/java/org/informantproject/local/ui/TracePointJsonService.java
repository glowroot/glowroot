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
import java.util.concurrent.TimeUnit;

import org.informantproject.core.config.ConfigService;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.Clock;
import org.informantproject.local.trace.StoredTraceDuration;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.trace.TraceDao.StringComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
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

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final ConfigService configService;
    private final Ticker ticker;
    private final Clock clock;
    private final Gson gson = new Gson();

    @Inject
    TracePointJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            ConfigService configService, Ticker ticker, Clock clock) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.configService = configService;
        this.ticker = ticker;
        this.clock = clock;
    }

    @JsonServiceMethod
    String getPoints(String message) throws IOException {
        logger.debug("getChartPoints(): message={}", message);
        TraceRequest request = gson.fromJson(message, TraceRequest.class);
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
        StringComparator usernameComparator = null;
        String comparatorText = request.getUsernameComparator();
        if (comparatorText != null) {
            usernameComparator = StringComparator.valueOf(comparatorText
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
            activeTraces = getActiveTraces(low, high);
            // take capture timings after the capture to make sure there no traces captured that
            // start after the recorded capture time (resulting in negative duration)
            capturedAt = clock.currentTimeMillis();
            captureTick = ticker.read();
        }
        if (request.getTo() == 0) {
            request.setTo(requestAt);
        }
        List<StoredTraceDuration> storedTraceDurations = traceDao.readStoredTraceDurations(
                request.getFrom(), request.getTo(), low, high, usernameComparator,
                request.getUsername());
        // remove duplicates between active and stored traces
        for (Iterator<Trace> i = activeTraces.iterator(); i.hasNext();) {
            Trace activeTrace = i.next();
            for (Iterator<StoredTraceDuration> j = storedTraceDurations.iterator(); j.hasNext();) {
                StoredTraceDuration storedTraceDuration = j.next();
                if (activeTrace.getId().equals(storedTraceDuration.getId())) {
                    // prefer stored trace if it is completed, otherwise prefer active trace
                    if (storedTraceDuration.isCompleted()) {
                        i.remove();
                    } else {
                        j.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }
        return writeResponse(storedTraceDurations, activeTraces, capturedAt, captureTick);
    }

    private List<Trace> getActiveTraces(long low, long high) {
        List<Trace> activeTraces = Lists.newArrayList();
        long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(configService.getCoreConfig()
                .getThresholdMillis());
        for (Trace trace : traceRegistry.getTraces()) {
            long duration = trace.getDuration();
            if (duration >= thresholdNanos && duration >= low && duration <= high) {
                activeTraces.add(trace);
            } else {
                // the traces are ordered by start time so it's safe to break now
                break;
            }
        }
        return activeTraces;
    }

    private static String writeResponse(List<StoredTraceDuration> storedTraceDurations,
            List<Trace> activeTraces, long capturedAt, long captureTick) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("activeTracePoints").beginArray();
        for (Trace activeTrace : activeTraces) {
            jw.beginArray();
            jw.value(capturedAt);
            jw.value((captureTick - activeTrace.getStartTick()) / 1000000000.0);
            jw.value(activeTrace.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.name("storedTracePoints").beginArray();
        for (StoredTraceDuration storedTraceDuration : storedTraceDurations) {
            jw.beginArray();
            jw.value(storedTraceDuration.getCapturedAt());
            jw.value(storedTraceDuration.getDuration() / 1000000000.0);
            jw.value(storedTraceDuration.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
