/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.local.metrics;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.informantproject.local.ui.LocalHttpHandler.JsonService;
import org.informantproject.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read metrics data. Bound to url "/metrics" in LocalModule.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class MetricJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(MetricJsonService.class);

    private static final int DONT_SEND_END_TIME_IN_RESPONSE = -1;

    private final MetricPointDao metricPointDao;
    private final Clock clock;

    @Inject
    public MetricJsonService(MetricPointDao metricPointDao, Clock clock) {
        this.metricPointDao = metricPointDao;
        this.clock = clock;
    }

    public String handleRequest(String message) throws IOException {
        logger.debug("handleRequest(): message={}", message);
        ReadMetricsRequest request = new Gson().fromJson(message, ReadMetricsRequest.class);
        if (request.getStart() < 0) {
            request.setStart(clock.currentTimeMillis() + request.getStart());
        }
        boolean isEndCurrentTime = (request.getEnd() == 0);
        if (isEndCurrentTime) {
            request.setEnd(clock.currentTimeMillis());
        }
        Map<String, List<Point>> metricPoints = metricPointDao.readMetricPoints(
                request.getMetricIds(), request.getStart(), request.getEnd());
        String response;
        if (isEndCurrentTime) {
            response = writeResponse(metricPoints, request.getStart(), request.getEnd());
        } else {
            response = writeResponse(metricPoints, request.getStart());
        }
        logger.debug("onMessage(): response={}", response);
        return response;
    }

    private static String writeResponse(Map<String, List<Point>> metricPoints, long start)
            throws IOException {

        return writeResponse(metricPoints, start, DONT_SEND_END_TIME_IN_RESPONSE);
    }

    private static String writeResponse(Map<String, List<Point>> metricPoints, long start, long end)
            throws IOException {

        StringWriter sw = new StringWriter();
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("start").value(start);
        if (end != DONT_SEND_END_TIME_IN_RESPONSE) {
            jw.name("end").value(end);
        }
        jw.name("data").beginObject();
        for (Entry<String, List<Point>> entry : metricPoints.entrySet()) {
            jw.name(entry.getKey()).beginArray();
            for (Point point : entry.getValue()) {
                jw.beginArray();
                jw.value(point.getCapturedAt() - start);
                jw.value(point.getValue());
                jw.endArray();
            }
            jw.endArray();
        }
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
