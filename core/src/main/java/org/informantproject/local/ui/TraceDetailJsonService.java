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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.informantproject.api.LargeStringBuilder;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.local.trace.StackTraceDao;
import org.informantproject.local.trace.StoredTrace;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data. Bound to url "/trace/details" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceDetailJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceDetailJsonService.class);

    private static final int NANOSECONDS_PER_MILLISECOND = 1000000;

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final StackTraceDao stackTraceDao;

    @Inject
    public TraceDetailJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            StackTraceDao stackTraceDao) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.stackTraceDao = stackTraceDao;
    }

    public String handleDetails(String message) throws IOException {
        logger.debug("handleDetails(): message={}", message);
        TraceRequest request = new Gson().fromJson(message, TraceRequest.class);
        long from = request.getFrom();
        long to = request.getTo() == 0 ? Long.MAX_VALUE : request.getTo();
        // since low and high are qualified using <= (instead of <), and precision in the database
        // is in whole nanoseconds, ceil(low) and floor(high) give the correct final result even in
        // cases where low and high are not in whole nanoseconds
        long low = (long) Math.ceil(request.getLow() * NANOSECONDS_PER_MILLISECOND);
        long high = request.getHigh() == 0 ? Long.MAX_VALUE : (long) Math.floor(request.getHigh()
                * NANOSECONDS_PER_MILLISECOND);
        List<StoredTrace> storedTraces = traceDao.readStoredTraces(from, to, low, high);
        List<Trace> activeTraces = new ArrayList<Trace>();
        processExtraIds(request.getExtraIds(), storedTraces, activeTraces);
        String response = writeResponse(storedTraces, activeTraces);
        if (response.length() <= 2000) {
            logger.debug("handleDetails(): response={}", response);
        } else {
            logger.debug("handleDetails(): response={}...", response.substring(0, 2000));
        }
        return response;
    }

    private void processExtraIds(String extraIdsParam, List<StoredTrace> storedTraces,
            List<Trace> activeTraces) {

        if (extraIdsParam == null || extraIdsParam.length() == 0) {
            return;
        }
        List<String> extraIds = Lists.newArrayList(extraIdsParam.split(","));
        // check active traces for the extra ids first
        for (final Trace trace : traceRegistry.getTraces()) {
            if (extraIds.contains(trace.getId())) {
                activeTraces.add(trace);
                extraIds.remove(trace.getId());
                if (trace.isStuck()) {
                    // remove possible stuck stored trace with same id
                    Collections2.filter(storedTraces, new Predicate<StoredTrace>() {
                        public boolean apply(StoredTrace storedTrace) {
                            return storedTrace.getId().equals(trace.getId());
                        }
                    });
                }
            }
        }
        // if any extra ids were not found in the active traces, then they must have completed
        // so read them from trace dao
        for (String extraId : extraIds) {
            StoredTrace extraTrace = traceDao.readStoredTrace(extraId);
            if (extraTrace == null) {
                logger.warn("requested extra id '{}' not found in either active or stored"
                        + " traces", extraId);
            } else {
                storedTraces.add(extraTrace);
            }
        }
    }

    private String writeResponse(List<StoredTrace> storedTraces,
            List<Trace> activeTraces) throws IOException {

        // activeTraces is already sorted by oldest first (since they were pulled out of
        // TraceRegistry in order), which is the same as largest duration first (for active traces)

        // order storedTraces by largest duration first as well
        Collections.sort(storedTraces, new Comparator<StoredTrace>() {
            public int compare(StoredTrace storedTrace1, StoredTrace storedTrace2) {
                // can't just subtract durations and cast to int because of int overflow
                return storedTrace1.getDuration() >= storedTrace2.getDuration() ? -1 : 1;
            }
        });

        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        if (!activeTraces.isEmpty()) {
            writeActiveTraces(sb, jw, activeTraces);
        }
        if (!storedTraces.isEmpty()) {
            writeStoredTraces(sb, jw, storedTraces);
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private static void writeStoredTraces(Appendable sb, JsonWriter jw,
            List<StoredTrace> storedTraces) throws IOException {

        for (StoredTrace storedTrace : storedTraces) {
            jw.beginObject();
            jw.name("id").value(storedTrace.getId());
            jw.name("start").value(storedTrace.getStartAt());
            jw.name("stuck").value(storedTrace.isStuck());
            jw.name("duration").value(storedTrace.getDuration());
            jw.name("completed").value(storedTrace.isCompleted());
            jw.name("description").value(storedTrace.getDescription());
            if (storedTrace.getUsername() != null) {
                jw.name("username").value(storedTrace.getUsername());
            }
            // inject raw json into stream
            if (storedTrace.getMetrics() != null) {
                sb.append(",\"metrics\":");
                sb.append(storedTrace.getMetrics());
            }
            if (storedTrace.getContextMap() != null) {
                sb.append(",\"contextMap\":");
                sb.append(storedTrace.getContextMap());
            }
            if (storedTrace.getSpans() != null) {
                // spans could be null if spans text has been rolled out
                sb.append(",\"spans\":");
                sb.append(storedTrace.getSpans());
            }
            if (storedTrace.getMergedStackTree() != null) {
                sb.append(",\"mergedStackTree\":");
                sb.append(storedTrace.getMergedStackTree());
            }
            jw.endObject();
        }
    }

    // TODO there is no unit or integration test that hits this code
    private void writeActiveTraces(Appendable sb, JsonWriter jw,
            List<Trace> activeTraces) throws IOException {

        // there is a chance for slight inconsistency since this is reading active traces which are
        // still being modified and/or may even reach completion while they are being written
        for (Trace activeTrace : activeTraces) {
            jw.beginObject();
            jw.name("active").value(true);
            jw.name("id").value(activeTrace.getId());
            jw.name("start").value(activeTrace.getStartDate().getTime());
            jw.name("stuck").value(activeTrace.isStuck());
            jw.name("duration").value(activeTrace.getDuration());
            jw.name("completed").value(false);
            Span rootSpan = activeTrace.getRootSpan().getSpans().iterator().next();
            jw.name("description").value(rootSpan.getDescription().toString());
            if (activeTrace.getUsername() != null) {
                jw.name("username").value(activeTrace.getUsername());
            }
            Gson gson = new Gson();
            String metrics = TraceSinkLocal.getMetricsJson(activeTrace, gson);
            String contextMap = TraceSinkLocal.getContextMapJson(activeTrace, gson);
            Map<String, String> stackTraces = new HashMap<String, String>();
            CharSequence spans = TraceSinkLocal.getSpansJson(activeTrace, stackTraces, gson);
            stackTraceDao.storeStackTraces(stackTraces);
            CharSequence mergedStackTree = TraceSinkLocal.getMergedStackTreeJson(activeTrace);
            // inject raw json into stream
            if (metrics != null) {
                sb.append(",\"metrics\":");
                sb.append(metrics);
            }
            if (contextMap != null) {
                sb.append(",\"contextMap\":");
                sb.append(contextMap);
            }
            sb.append(",\"spans\":");
            sb.append(spans);
            if (mergedStackTree != null) {
                sb.append(",\"mergedStackTree\":");
                sb.append(mergedStackTree);
            }
            jw.endObject();
        }
    }
}
