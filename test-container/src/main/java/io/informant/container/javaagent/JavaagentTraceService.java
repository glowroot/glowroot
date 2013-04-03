/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.container.javaagent;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.informant.container.common.ObjectMappers;
import io.informant.container.javaagent.TracePointResponse.RawPoint;
import io.informant.container.trace.Trace;
import io.informant.container.trace.TraceService;
import io.informant.markers.ThreadSafe;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class JavaagentTraceService implements TraceService {

    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final JavaagentHttpClient httpClient;

    JavaagentTraceService(JavaagentHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Nullable
    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    @Nullable
    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, false);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTraceSummary(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, true);
    }

    public int getNumPendingCompleteTraces() throws Exception {
        String numPendingCompleteTraces = httpClient.get("/admin/num-pending-complete-traces");
        return Integer.parseInt(numPendingCompleteTraces);
    }

    public long getNumStoredSnapshots() throws Exception {
        String numStoredSnapshots = httpClient.get("/admin/num-stored-snapshots");
        return Long.parseLong(numStoredSnapshots);
    }

    public InputStream getTraceExport(String traceId) throws Exception {
        return httpClient.getAsStream("/explorer/export/" + traceId);
    }

    void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTraces = Integer.parseInt(httpClient.get("/admin/num-active-traces"));
            if (numActiveTraces == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }

    void deleteAllSnapshots() throws Exception {
        httpClient.post("/admin/data/delete-all", "");
    }

    @Nullable
    private Trace getActiveTrace(int timeout, TimeUnit unit, boolean summary) throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            trace = getActiveTrace(summary);
            if (trace != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return trace;
    }

    @Nullable
    private Trace getLastTrace(boolean summary) throws Exception {
        String content = httpClient.get("/explorer/points?from=0&to=" + Long.MAX_VALUE
                + "&low=0&high=" + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        List<RawPoint> points = Lists.newArrayList();
        points.addAll(response.getNormalPoints());
        points.addAll(response.getErrorPoints());
        if (points.isEmpty()) {
            return null;
        }
        RawPoint mostRecentCapturedPoint = RawPoint.orderingByCapturedAt.max(points);
        if (summary) {
            String traceContent =
                    httpClient.get("/explorer/summary/" + mostRecentCapturedPoint.getId());
            Trace trace =
                    ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
            trace.setSummary(true);
            return trace;
        } else {
            String traceContent =
                    httpClient.get("/explorer/detail/" + mostRecentCapturedPoint.getId());
            return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
        }
    }

    @Nullable
    private Trace getActiveTrace(boolean summary) throws Exception {
        String content = httpClient.get("/explorer/points?from=0&to=" + Long.MAX_VALUE
                + "&low=0&high=" + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response =
                ObjectMappers.readRequiredValue(mapper, content, TracePointResponse.class);
        if (response.getActivePoints().isEmpty()) {
            return null;
        } else if (response.getActivePoints().size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            RawPoint point = response.getActivePoints().get(0);
            if (summary) {
                String traceContent = httpClient.get("/explorer/summary/" + point.getId());
                Trace trace = ObjectMappers.readRequiredValue(mapper, traceContent,
                        Trace.class);
                trace.setSummary(true);
                return trace;
            } else {
                String traceContent = httpClient.get("/explorer/detail/" + point.getId());
                return ObjectMappers.readRequiredValue(mapper, traceContent, Trace.class);
            }
        }
    }
}
