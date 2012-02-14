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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.local.trace.StoredTraceSummary;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceRegistry;
import org.informantproject.util.Clock;
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
        List<StoredTraceSummary> liveSummaries = new ArrayList<StoredTraceSummary>();
        if (request.getTo() == 0 || request.getTo() > clock.currentTimeMillis()) {
            // capture live traces first to make sure that none are missed in between reading stored
            // traces and then capturing live traces, possible duplicates are removed below
            long thresholdNanos = TimeUnit.MILLISECONDS.toNanos(configurationService
                    .getCoreConfiguration().getThresholdMillis());
            for (Trace trace : traceRegistry.getTraces()) {
                long duration = trace.getDuration();
                if (duration >= thresholdNanos) {
                    liveSummaries.add(new StoredTraceSummary(trace.getId(), clock
                            .currentTimeMillis(), duration, false));
                } else {
                    // the traces are ordered by start time
                    break;
                }
            }
        }
        if (request.getTo() == 0) {
            request.setTo(clock.currentTimeMillis());
        }
        List<StoredTraceSummary> storedSummaries = traceDao.readStoredTraceSummaries(
                request.getFrom(), request.getTo());

        // TODO first remove duplicates within stored summaries (stuck/unstuck pair)

        // remove duplicates between live and stored summaries
        for (Iterator<StoredTraceSummary> i = liveSummaries.iterator(); i.hasNext();) {
            StoredTraceSummary liveSummary = i.next();
            for (Iterator<StoredTraceSummary> j = storedSummaries.iterator(); j.hasNext();) {
                StoredTraceSummary storedSummary = j.next();
                if (liveSummary.getId().equals(storedSummary.getId())) {
                    // prefer stored summary if it is completed, otherwise prefer live summary
                    if (storedSummary.isCompleted()) {
                        i.remove();
                    } else {
                        j.remove();
                    }
                    // there can be at most one duplicate per id, so ok to break to outer
                    break;
                }
            }
        }
        String response = writeResponse(storedSummaries, liveSummaries);
        logger.debug("handleSummaries(): response={}", response);
        return response;
    }

    private static String writeResponse(List<StoredTraceSummary> storedTraceSummaries,
            List<StoredTraceSummary> liveTraceSummaries) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("storedData").beginArray();
        for (StoredTraceSummary storedTraceSummary : storedTraceSummaries) {
            jw.beginArray();
            jw.value(storedTraceSummary.getCapturedAt());
            jw.value(storedTraceSummary.getDuration() / 1000000000.0);
            jw.endArray();
        }
        jw.endArray();
        jw.name("liveData").beginArray();
        for (StoredTraceSummary liveTraceSummary : liveTraceSummaries) {
            jw.beginArray();
            jw.value(liveTraceSummary.getCapturedAt());
            jw.value(liveTraceSummary.getDuration() / 1000000000.0);
            jw.value(liveTraceSummary.getId());
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
