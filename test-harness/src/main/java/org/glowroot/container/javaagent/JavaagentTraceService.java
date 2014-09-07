/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.javaagent;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.javaagent.TracePointResponse.RawPoint;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;
import org.glowroot.container.trace.TraceService;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
class JavaagentTraceService extends TraceService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final JavaagentHttpClient httpClient;

    JavaagentTraceService(JavaagentHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public int getNumPendingCompleteTransactions() throws Exception {
        String numPendingCompleteTransactions =
                httpClient.get("/backend/admin/num-pending-complete-transactions");
        return Integer.parseInt(numPendingCompleteTransactions);
    }

    @Override
    public long getNumTraces() throws Exception {
        String numTraces = httpClient.get("/backend/admin/num-traces");
        return Long.parseLong(numTraces);
    }

    @Override
    public InputStream getTraceExport(String traceId) throws Exception {
        return httpClient.getAsStream("/export/trace/" + traceId);
    }

    @Override
    @Nullable
    public Trace getLastTrace() throws Exception {
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
        String traceContent = httpClient.get("/backend/trace/header/"
                + mostRecentCapturedPoint.getId());
        return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
    }

    @Override
    @Nullable
    protected Trace getActiveTrace() throws Exception {
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
            String traceContent = httpClient.get("/backend/trace/header/" + point.getId());
            return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
        }
    }

    @Override
    @Nullable
    public List<TraceEntry> getEntries(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/entries?trace-id=" + traceId);
        return mapper.readValue(content, new TypeReference<List<TraceEntry>>() {});
    }

    @Override
    @Nullable
    public ProfileNode getProfile(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/profile?trace-id=" + traceId);
        return mapper.readValue(content, ProfileNode.class);
    }

    @Override
    @Nullable
    public ProfileNode getOutlierProfile(String traceId) throws Exception {
        String content = httpClient.get("/backend/trace/outlier-profile?trace-id=" + traceId);
        return mapper.readValue(content, ProfileNode.class);
    }

    @Override
    public void deleteAll() throws Exception {
        httpClient.post("/backend/admin/delete-all-aggregates", "");
        httpClient.post("/backend/admin/delete-all-traces", "");
    }

    void assertNoActiveTransactions() throws Exception {
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
}
