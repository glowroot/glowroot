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
package org.informantproject.local.trace;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import org.informantproject.local.ui.HttpServer.JsonService;
import org.informantproject.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data. Bound to url "/traces" in LocalModule.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceJsonService.class);

    private static final int DONT_SEND_END_TIME_IN_RESPONSE = -1;

    private final TraceDao traceDao;
    private final Clock clock;

    @Inject
    public TraceJsonService(TraceDao traceDao, Clock clock) {
        this.traceDao = traceDao;
        this.clock = clock;
    }

    public String handleRequest(String message) throws IOException {
        logger.debug("onMessage(): message={}", message);
        ReadTracesRequest request = new Gson().fromJson(message, ReadTracesRequest.class);
        if (request.getStart() < 0) {
            request.setStart(clock.currentTimeMillis() + request.getStart());
        }
        boolean isEndCurrentTime = (request.getEnd() == 0);
        if (isEndCurrentTime) {
            request.setEnd(clock.currentTimeMillis());
        }
        List<StoredTrace> traces = traceDao.readStoredTraces(request.getStart(), request.getEnd());
        String response;
        if (isEndCurrentTime) {
            response = writeResponse(traces, request.getStart(), request.getEnd());
        } else {
            response = writeResponse(traces, request.getStart());
        }
        logger.debug("onMessage(): response={}", response);
        return response;
    }

    private static String writeResponse(List<StoredTrace> storedTraces, long start)
            throws IOException {

        return writeResponse(storedTraces, start, DONT_SEND_END_TIME_IN_RESPONSE);
    }

    private static String writeResponse(List<StoredTrace> storedTraces, long start, long end)
            throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("start").value(start);
        if (end != DONT_SEND_END_TIME_IN_RESPONSE) {
            jw.name("end").value(end);
        }
        jw.name("traces").beginArray();
        for (StoredTrace storedTrace : storedTraces) {
            jw.beginObject();
            jw.name("id").value(storedTrace.getId());
            jw.name("start").value(storedTrace.getStartAt());
            jw.name("stuck").value(storedTrace.isStuck());
            jw.name("uniqueId").value(storedTrace.getId());
            jw.name("duration").value(storedTrace.getDuration());
            jw.name("completed").value(storedTrace.isCompleted());
            // inject raw json into stream
            sw.write(",\"threadNames\":");
            sw.write(storedTrace.getThreadNames());
            jw.name("username").value(storedTrace.getUsername());
            sw.write(",\"spans\":");
            sw.write(storedTrace.getSpans());
            // TODO write metric data, trace and merged stack tree
            jw.endObject();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
