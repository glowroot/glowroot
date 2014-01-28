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

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.javaagent.TracePointResponse.RawPoint;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceService;
import org.glowroot.markers.ThreadSafe;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class JavaagentTraceService extends TraceService {

    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final JavaagentHttpClient httpClient;

    JavaagentTraceService(JavaagentHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public int getNumPendingCompleteTraces() throws Exception {
        String numPendingCompleteTraces =
                httpClient.get("/backend/admin/num-pending-complete-traces");
        return Integer.parseInt(numPendingCompleteTraces);
    }

    @Override
    public long getNumStoredSnapshots() throws Exception {
        String numStoredSnapshots = httpClient.get("/backend/admin/num-stored-snapshots");
        return Long.parseLong(numStoredSnapshots);
    }

    @Override
    public InputStream getTraceExport(String traceId) throws Exception {
        return httpClient.getAsStream("/export/" + traceId);
    }

    void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTraces = Integer.parseInt(httpClient
                    .get("/backend/admin/num-active-traces"));
            if (numActiveTraces == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }

    void deleteAllSnapshots() throws Exception {
        httpClient.post("/backend/admin/data/delete-all", "");
    }

    @Override
    @Nullable
    protected Trace getLastTrace(boolean summary) throws Exception {
        String content = httpClient.get("/backend/trace/points?from=0&to=" + Long.MAX_VALUE
                + "&low=0&high=" + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<RawPoint> points = Lists.newArrayList();
        points.addAll(response.getNormalPoints());
        points.addAll(response.getErrorPoints());
        if (points.isEmpty()) {
            return null;
        }
        RawPoint mostRecentCapturedPoint = RawPoint.orderingByCaptureTime.max(points);
        String path = summary ? "/backend/trace/summary/" : "/backend/trace/detail/";
        String traceContent = httpClient.get(path + mostRecentCapturedPoint.getId());
        return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
    }

    @Override
    @Nullable
    protected Trace getActiveTrace(boolean summary) throws Exception {
        String content = httpClient.get("/backend/trace/points?from=0&to=" + Long.MAX_VALUE
                + "&low=0&high=" + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        if (response.getActivePoints().isEmpty()) {
            return null;
        } else if (response.getActivePoints().size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            RawPoint point = response.getActivePoints().get(0);
            String path = summary ? "/backend/trace/summary/" : "/backend/trace/detail/";
            String traceContent = httpClient.get(path + point.getId());
            return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
        }
    }
}
