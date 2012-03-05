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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.Clock;
import org.informantproject.local.trace.StoredTraceDuration;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data. Bound to url "/trace/durations" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceDurationJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceDurationJsonService.class);

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final ConfigurationService configurationService;
    private final Ticker ticker;
    private final Clock clock;

    @Inject
    public TraceDurationJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            ConfigurationService configurationService, Ticker ticker, Clock clock) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.configurationService = configurationService;
        this.ticker = ticker;
        this.clock = clock;
    }

    public String handleDurations(String message) throws IOException {
        logger.debug("handleDurations(): message={}", message);
        TraceRequest request = new Gson().fromJson(message, TraceRequest.class);
        long requestAt = clock.currentTimeMillis();
        if (request.getFrom() < 0) {
            request.setFrom(requestAt + request.getFrom());
        }
        List<Trace> activeTraces = Collections.emptyList();
        long capturedAt = 0;
        long capturedTick = 0;
        if (request.getTo() == 0 || request.getTo() > requestAt) {
            // capture active traces first to make sure that none are missed in between reading
            // stored traces and then capturing active traces (possible duplicates are removed
            // below)
            activeTraces = getActiveTraces();
            // take capture timings after the capture to make sure there no traces captured that
            // start after the recorded capture time (resulting in negative duration)
            capturedAt = clock.currentTimeMillis();
            capturedTick = ticker.read();
        }
        if (request.getTo() == 0) {
            request.setTo(requestAt);
        }
        List<StoredTraceDuration> storedTraceDurations = traceDao.readStoredTraceDurations(
                request.getFrom(), request.getTo());
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
        String response = writeResponse(storedTraceDurations, activeTraces, capturedAt,
                capturedTick);
        logger.debug("handleDurations(): response={}", response);
        return response;
    }

    private List<Trace> getActiveTraces() {
        List<Trace> activeTraces = new ArrayList<Trace>();
        long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(configurationService
                .getCoreConfiguration().getThresholdMillis());
        for (Trace trace : traceRegistry.getTraces()) {
            long duration = trace.getDuration();
            if (duration >= thresholdNanos) {
                activeTraces.add(trace);
            } else {
                // the traces are ordered by start time so it's safe to break now
                break;
            }
        }
        return activeTraces;
    }

    private static String writeResponse(List<StoredTraceDuration> storedTraceDurations,
            List<Trace> activeTraces, long capturedAt, long capturedTick) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("activeTraceDurations").beginArray();
        for (Trace activeTrace : activeTraces) {
            jw.beginArray();
            jw.value(capturedAt);
            jw.value((capturedTick - activeTrace.getStartTime()) / 1000000000.0);
            jw.value(activeTrace.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.name("storedTraceDurations").beginArray();
        for (StoredTraceDuration storedTraceDuration : storedTraceDurations) {
            jw.beginArray();
            jw.value(storedTraceDuration.getCapturedAt());
            jw.value(storedTraceDuration.getDuration() / 1000000000.0);
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
