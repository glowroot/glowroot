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
import org.informantproject.local.trace.StoredTraceSummary;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data. Bound to url "/trace/summaries" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSummaryJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSummaryJsonService.class);

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final ConfigurationService configurationService;
    private final Clock clock;

    @Inject
    public TraceSummaryJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            ConfigurationService configurationService, Clock clock) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.configurationService = configurationService;
        this.clock = clock;
    }

    public String handleSummaries(String message) throws IOException {
        logger.debug("handleSummaries(): message={}", message);
        TraceRequest request = new Gson().fromJson(message, TraceRequest.class);
        if (request.getFrom() < 0) {
            request.setFrom(clock.currentTimeMillis() + request.getFrom());
        }
        List<StoredTraceSummary> activeTraceSummaries;
        if (request.getTo() == 0 || request.getTo() > clock.currentTimeMillis()) {
            // capture active traces first to make sure that none are missed in between reading
            // stored traces and then capturing active traces (possible duplicates are removed
            // below)
            activeTraceSummaries = getActiveTraceSummaries();
        } else {
            activeTraceSummaries = Collections.emptyList();
        }
        if (request.getTo() == 0) {
            request.setTo(clock.currentTimeMillis());
        }
        List<StoredTraceSummary> storedTraceSummaries = traceDao.readStoredTraceSummaries(
                request.getFrom(), request.getTo());
        // remove duplicates between active and stored trace summaries
        for (Iterator<StoredTraceSummary> i = activeTraceSummaries.iterator(); i.hasNext();) {
            StoredTraceSummary activeTraceSummary = i.next();
            for (Iterator<StoredTraceSummary> j = storedTraceSummaries.iterator(); j.hasNext();) {
                StoredTraceSummary storedTraceSummary = j.next();
                if (activeTraceSummary.getId().equals(storedTraceSummary.getId())) {
                    // prefer stored trace if it is completed, otherwise prefer active trace
                    if (storedTraceSummary.isCompleted()) {
                        i.remove();
                    } else {
                        j.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }
        String response = writeResponse(storedTraceSummaries, activeTraceSummaries);
        logger.debug("handleSummaries(): response={}", response);
        return response;
    }

    private List<StoredTraceSummary> getActiveTraceSummaries() {
        List<StoredTraceSummary> activeTraceSummaries = new ArrayList<StoredTraceSummary>();
        long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(configurationService
                .getCoreConfiguration().getThresholdMillis());
        for (Trace trace : traceRegistry.getTraces()) {
            long duration = trace.getDuration();
            if (duration >= thresholdNanos) {
                activeTraceSummaries.add(new StoredTraceSummary(trace.getId(), clock
                        .currentTimeMillis(), duration, false));
            } else {
                // the traces are ordered by start time
                break;
            }
        }
        return activeTraceSummaries;
    }

    private static String writeResponse(List<StoredTraceSummary> storedTraceSummaries,
            List<StoredTraceSummary> activeTraceSummaries) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("storedTraces").beginArray();
        for (StoredTraceSummary storedTraceSummary : storedTraceSummaries) {
            jw.beginArray();
            jw.value(storedTraceSummary.getCapturedAt());
            jw.value(storedTraceSummary.getDuration() / 1000000000.0);
            jw.endArray();
        }
        jw.endArray();
        jw.name("activeTraces").beginArray();
        for (StoredTraceSummary activeTraceSummary : activeTraceSummaries) {
            jw.beginArray();
            jw.value(activeTraceSummary.getCapturedAt());
            jw.value(activeTraceSummary.getDuration() / 1000000000.0);
            jw.value(activeTraceSummary.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
