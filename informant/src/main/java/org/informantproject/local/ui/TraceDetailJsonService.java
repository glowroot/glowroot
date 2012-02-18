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
import java.util.Collections;
import java.util.List;

import org.informantproject.local.trace.StoredTrace;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.informantproject.trace.Trace;
import org.informantproject.trace.TraceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final TraceSinkLocal traceSinkLocal;

    @Inject
    public TraceDetailJsonService(TraceDao traceDao, TraceRegistry traceRegistry,
            TraceSinkLocal traceSinkLocal) {

        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.traceSinkLocal = traceSinkLocal;
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
        if (request.getExtraIds() != null) {
            // check live traces for the extra ids first
            List<String> extraIds;
            if (request.getExtraIds().length() == 0) {
                extraIds = Collections.emptyList();
            } else {
                extraIds = Lists.newArrayList(request.getExtraIds().split(","));
            }
            for (Trace trace : traceRegistry.getTraces()) {
                if (extraIds.contains(trace.getId())) {
                    storedTraces.add(traceSinkLocal.buildStoredTrace(trace));
                    extraIds.remove(trace.getId());
                }
            }
            // if any extra ids were not found in the live traces, then they must have completed so
            // read them from trace dao
            for (String extraId : extraIds) {
                List<StoredTrace> extraTraces = traceDao.readStoredTraces(extraId);
                if (!extraTraces.isEmpty()) {
                    storedTraces.add(extraTraces.get(extraTraces.size() - 1));
                } else {
                    logger.warn("requested extra id '{}' not found in either live or stored"
                            + " traces", extraId);
                }
            }
        }
        String response = writeResponse(storedTraces);
        if (response.length() <= 2000) {
            logger.debug("handleDetails(): response={}", response);
        } else {
            logger.debug("handleDetails(): response={}...", response.substring(0, 2000));
        }
        return response;
    }

    private static String writeResponse(List<StoredTrace> storedTraces) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StoredTrace storedTrace : storedTraces) {
            jw.beginObject();
            jw.name("id").value(storedTrace.getId());
            jw.name("start").value(storedTrace.getStartAt());
            jw.name("stuck").value(storedTrace.isStuck());
            jw.name("uniqueId").value(storedTrace.getId());
            jw.name("duration").value(storedTrace.getDuration());
            jw.name("completed").value(storedTrace.isCompleted());
            // inject raw json into stream
            sb.append(",\"threadNames\":");
            sb.append(storedTrace.getThreadNames());
            if (storedTrace.getUsername() != null) {
                jw.name("username").value(storedTrace.getUsername());
            }
            sb.append(",\"rootSpan\":");
            sb.append(storedTrace.getRootSpan());
            if (storedTrace.getSpans() != null) {
                sb.append(",\"spans\":");
                sb.append(storedTrace.getSpans());
            }
            if (storedTrace.getMergedStackTree() != null) {
                sb.append(",\"mergedStackTree\":");
                sb.append(storedTrace.getMergedStackTree());
            }
            // TODO write metric data, trace and merged stack tree
            jw.endObject();
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }
}
