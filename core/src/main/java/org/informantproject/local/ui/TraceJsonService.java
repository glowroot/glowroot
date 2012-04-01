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
import java.util.HashMap;
import java.util.Map;

import org.informantproject.api.LargeStringBuilder;
import org.informantproject.api.Optional;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.core.util.OptionalJsonSerializer;
import org.informantproject.local.trace.StackTraceDao;
import org.informantproject.local.trace.StoredTrace;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
public class TraceJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceJsonService.class);

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final StackTraceDao stackTraceDao;
    private final Ticker ticker;

    @Inject
    public TraceJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            StackTraceDao stackTraceDao, Ticker ticker) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.stackTraceDao = stackTraceDao;
        this.ticker = ticker;
    }

    // called dynamically from HttpServer
    public String getSummary(String id) throws IOException {
        logger.debug("getSummary(): id={}", id);
        Optional<String> response = getStoredOrActiveTraceJson(id, false);
        if (response.isPresent()) {
            return response.get();
        } else {
            logger.error("no trace found for id '{}'", id);
            // TODO 404
            return null;
        }
    }

    // called dynamically from HttpServer
    public String getDetail(String id) throws IOException {
        logger.debug("getDetail(): id={}", id);
        Optional<String> response = getStoredOrActiveTraceJson(id, true);
        if (response.isPresent()) {
            return response.get();
        } else {
            logger.error("no trace found for id '{}'", id);
            // TODO 404
            return null;
        }
    }

    public Optional<String> getStoredOrActiveTraceJson(String id, boolean includeDetail)
            throws IOException {

        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                return Optional.of(getActiveTraceJson(active, includeDetail));
            }
        }
        StoredTrace storedTrace = traceDao.readStoredTrace(id);
        if (storedTrace == null) {
            return Optional.absent();
        } else {
            return Optional.of(getStoredTraceJson(storedTrace, includeDetail));
        }
    }

    private String getStoredTraceJson(StoredTrace storedTrace, boolean includeDetail)
            throws IOException {

        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        writeStoredTrace(storedTrace, jw, sb, includeDetail);
        jw.close();
        return sb.build().toString();
    }

    private String getActiveTraceJson(Trace activeTrace, boolean includeDetail) throws IOException {
        long captureTick = ticker.read();
        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        Map<String, String> stackTraces = new HashMap<String, String>();
        writeActiveTrace(activeTrace, stackTraces, captureTick, jw, sb, includeDetail);
        if (!stackTraces.isEmpty()) {
            stackTraceDao.storeStackTraces(stackTraces);
        }
        jw.close();
        return sb.build().toString();
    }

    public static void writeStoredTrace(StoredTrace storedTrace, JsonWriter jw, Appendable sb,
            boolean includeDetail) throws IOException {

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
        if (storedTrace.getAttributes() != null) {
            sb.append(",\"attributes\":");
            sb.append(storedTrace.getAttributes());
        }
        if (storedTrace.getMetrics() != null) {
            sb.append(",\"metrics\":");
            sb.append(storedTrace.getMetrics());
        }
        if (includeDetail && storedTrace.getSpans() != null) {
            // spans could be null if spans text has been rolled out
            sb.append(",\"spans\":");
            sb.append(storedTrace.getSpans());
        }
        if (includeDetail && storedTrace.getMergedStackTree() != null) {
            sb.append(",\"mergedStackTree\":");
            sb.append(storedTrace.getMergedStackTree());
        }
        jw.endObject();
    }

    // TODO there is no unit or integration test that hits this code
    public static void writeActiveTrace(Trace activeTrace, Map<String, String> stackTraces,
            long captureTick, JsonWriter jw, Appendable sb, boolean includeDetail)
            throws IOException {

        // there is a chance for slight inconsistency since this is reading active traces which are
        // still being modified and/or may even reach completion while they are being written
        jw.beginObject();
        jw.name("active").value(true);
        jw.name("id").value(activeTrace.getId());
        jw.name("start").value(activeTrace.getStartDate().getTime());
        jw.name("stuck").value(activeTrace.isStuck());
        jw.name("duration").value(activeTrace.getDuration());
        jw.name("completed").value(false);
        Span rootSpan = activeTrace.getRootSpan().getSpans().iterator().next();
        jw.name("description").value(rootSpan.getDescription().toString());
        Optional<String> username = activeTrace.getUsername();
        if (username.isPresent()) {
            jw.name("username").value(username.get());
        }
        // OptionalJsonSerializer is needed for serializing trace attributes and span context maps
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
                new OptionalJsonSerializer()).create();
        String attributes = TraceSinkLocal.getAttributesJson(activeTrace, gson);
        if (attributes != null) {
            sb.append(",\"attributes\":");
            sb.append(attributes);
        }
        String metrics = TraceSinkLocal.getMetricsJson(activeTrace, gson);
        if (metrics != null) {
            sb.append(",\"metrics\":");
            sb.append(metrics);
        }
        if (includeDetail) {
            CharSequence spans = TraceSinkLocal.getSpansJson(activeTrace, stackTraces, captureTick,
                    gson);
            sb.append(",\"spans\":");
            sb.append(spans);
            CharSequence mergedStackTree = TraceSinkLocal.getMergedStackTreeJson(activeTrace);
            if (mergedStackTree != null) {
                sb.append(",\"mergedStackTree\":");
                sb.append(mergedStackTree);
            }
        }
        jw.endObject();
    }
}
