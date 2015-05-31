/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.container.trace;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

import org.glowroot.container.common.HttpClient;
import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.trace.TracePointResponse.RawPoint;

import static java.util.concurrent.TimeUnit.SECONDS;

//even though this is thread safe, it is not useful for running tests in parallel since
//getLastTrace() and others are not scoped to a particular test
public class TraceService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final HttpClient httpClient;

    public TraceService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public InputStream getTraceExport(String traceId) throws Exception {
        return httpClient.getAsStream("/export/trace/" + traceId);
    }

    public @Nullable Trace getLastTrace() throws Exception {
        String content = httpClient.get("/backend/trace/points?from=0&to=" + Long.MAX_VALUE
                + "&duration-low=0&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<RawPoint> points = Lists.newArrayList();
        points.addAll(response.getNormalPoints());
        points.addAll(response.getErrorPoints());
        if (points.isEmpty()) {
            return null;
        }
        RawPoint mostRecentCapturedPoint = RawPoint.orderingByCaptureTime.max(points);
        return getTrace(mostRecentCapturedPoint.getId());
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to hit its store
    // threshold
    public @Nullable Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            trace = getActiveTrace();
            if (trace != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return trace;
    }

    public List<Trace> getTraces(TraceQuery query) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("from=");
        sb.append(query.from());
        sb.append("&to=");
        sb.append(query.to());
        sb.append("&duration-low=");
        sb.append(query.durationLow());
        Long durationHigh = query.durationHigh();
        if (durationHigh != null) {
            sb.append("&duration-high=");
            sb.append(durationHigh);
        }
        String transactionType = query.transactionType();
        if (transactionType != null) {
            sb.append("&transaction-type=");
            sb.append(transactionType);
        }
        if (query.transactionName() != null) {
            sb.append("&transaction-name-comparator=");
            sb.append(query.transactionNameComparator());
            sb.append("&transaction-name=");
            sb.append(query.transactionName());
        }
        if (query.headline() != null) {
            sb.append("&headline-comparator=");
            sb.append(query.headlineComparator());
            sb.append("&headline=");
            sb.append(query.headline());
        }
        if (query.error() != null) {
            sb.append("&error-comparator=");
            sb.append(query.errorComparator());
            sb.append("&error=");
            sb.append(query.error());
        }
        if (query.user() != null) {
            sb.append("&user-comparator=");
            sb.append(query.userComparator());
            sb.append("&user=");
            sb.append(query.user());
        }
        if (query.customAttributeName() != null) {
            sb.append("&custom-attribute-name=");
            sb.append(query.customAttributeName());
            if (query.customAttributeValue() != null) {
                sb.append("&custom-attribute-value-comparator=");
                sb.append(query.customAttributeValueComparator());
                sb.append("&custom-attribute-value=");
                sb.append(query.customAttributeValue());
            }
        }
        sb.append("&error-only=");
        sb.append(query.errorOnly());
        sb.append("&limit=");
        sb.append(query.limit());
        String content = httpClient.get("/backend/trace/points?" + sb.toString());
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<Trace> traces = Lists.newArrayList();
        for (RawPoint point : response.getNormalPoints()) {
            traces.add(getTrace(point.getId()));
        }
        for (RawPoint point : response.getErrorPoints()) {
            traces.add(getTrace(point.getId()));
        }
        for (RawPoint point : response.getActivePoints()) {
            traces.add(getTrace(point.getId()));
        }
        return traces;
    }

    public List<TraceEntry> getEntries(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/entries?trace-id=" + traceId);
        return mapper.readValue(content, new TypeReference<List<TraceEntry>>() {});
    }

    public ProfileNode getProfile(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/profile?trace-id=" + traceId);
        return mapper.readValue(content, ProfileNode.class);
    }

    public void assertNoActiveTransactions() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // if interruptAppUnderTest() was used to terminate an active transaction, it may take a few
        // milliseconds to interrupt the thread and end the active transaction
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTransactions = Integer.parseInt(httpClient
                    .get("/backend/admin/num-active-transactions"));
            if (numActiveTransactions == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active transactions");
    }

    private @Nullable Trace getActiveTrace() throws Exception {
        String content = httpClient.get("/backend/trace/points?from=0&to=" + Long.MAX_VALUE
                + "&duration-low=0&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        if (response.getActivePoints().isEmpty()) {
            return null;
        } else if (response.getActivePoints().size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            RawPoint point = response.getActivePoints().get(0);
            return getTrace(point.getId());
        }
    }

    private Trace getTrace(String traceId) throws Exception {
        String traceContent = httpClient.get("/backend/trace/header/" + traceId);
        return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
    }
}
