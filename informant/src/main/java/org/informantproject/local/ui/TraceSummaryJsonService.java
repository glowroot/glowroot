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
import java.util.List;

import org.informantproject.local.trace.StoredTraceSummary;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.ui.HttpServer.JsonService;
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
// TODO look at reusable parts between TraceSummaryJsonService and TraceDetailJsonService
@Singleton
public class TraceSummaryJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(TraceSummaryJsonService.class);

    private static final int DONT_SEND_END_TIME_IN_RESPONSE = -1;

    private final TraceDao traceDao;
    private final Clock clock;

    @Inject
    public TraceSummaryJsonService(TraceDao traceDao, Clock clock) {
        this.traceDao = traceDao;
        this.clock = clock;
    }

    public String handleRequest(String message) throws IOException {
        logger.debug("handleRequest(): message={}", message);
        TraceRequest request = new Gson().fromJson(message, TraceRequest.class);
        if (request.getFrom() < 0) {
            request.setFrom(clock.currentTimeMillis() + request.getFrom());
        }
        boolean isEndCurrentTime = (request.getTo() == 0);
        if (isEndCurrentTime) {
            request.setTo(clock.currentTimeMillis());
        }
        List<StoredTraceSummary> traceSummaries = traceDao.readStoredTraceSummaries(
                request.getFrom(), request.getTo());
        String response;
        if (isEndCurrentTime) {
            response = writeResponse(traceSummaries, request.getFrom(), request.getTo());
        } else {
            response = writeResponse(traceSummaries, request.getFrom());
        }
        logger.debug("handleRequest(): response={}", response);
        return response;
    }

    private static String writeResponse(List<StoredTraceSummary> storedTraceSummaries, long start)
            throws IOException {

        return writeResponse(storedTraceSummaries, start, DONT_SEND_END_TIME_IN_RESPONSE);
    }

    private static String writeResponse(List<StoredTraceSummary> storedTraceSummaries, long start,
            long end) throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("start").value(start);
        if (end != DONT_SEND_END_TIME_IN_RESPONSE) {
            jw.name("end").value(end);
        }
        jw.name("data").beginArray();
        for (StoredTraceSummary storedTraceSummary : storedTraceSummaries) {
            jw.beginArray();
            jw.value(storedTraceSummary.getCapturedAt());
            jw.value(storedTraceSummary.getDuration() / 1000000000.0);
            jw.endArray();
        }
        jw.endArray();
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
