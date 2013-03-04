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
package io.informant.testkit;

import static java.util.concurrent.TimeUnit.SECONDS;
import io.informant.testkit.TracePointResponse.RawPoint;
import io.informant.testkit.internal.ObjectMappers;
import io.informant.util.ThreadSafe;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class ExternalJvmInformant implements Informant {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final int uiPort;
    private final AsyncHttpClient asyncHttpClient;

    ExternalJvmInformant(int uiPort, AsyncHttpClient asyncHttpClient) {
        this.uiPort = uiPort;
        this.asyncHttpClient = asyncHttpClient;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        GeneralConfig generalConfig = getGeneralConfig();
        generalConfig.setStoreThresholdMillis(storeThresholdMillis);
        updateGeneralConfig(generalConfig);
    }

    public InputStream getTraceExport(String traceId) throws Exception {
        BoundRequestBuilder request = asyncHttpClient.prepareGet("http://localhost:" + uiPort
                + "/explorer/export/" + traceId);
        Response response = request.execute().get();
        return validateAndReturnBodyAsStream(response);
    }

    public GeneralConfig getGeneralConfig() throws Exception {
        return getConfig().getGeneralConfig();
    }

    // returns new version
    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        String content = post("/config/general", mapper.writeValueAsString(config));
        return mapper.readValue(content, String.class);
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() throws Exception {
        return getConfig().getCoarseProfilingConfig();
    }

    // returns new version
    public String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        String content = post("/config/coarse-profiling", mapper.writeValueAsString(config));
        return mapper.readValue(content, String.class);
    }

    public FineProfilingConfig getFineProfilingConfig() throws Exception {
        return getConfig().getFineProfilingConfig();
    }

    // returns new version
    public String updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        String content = post("/config/fine-profiling", mapper.writeValueAsString(config));
        return mapper.readValue(content, String.class);
    }

    public UserConfig getUserConfig() throws Exception {
        return getConfig().getUserConfig();
    }

    // returns new version
    public String updateUserConfig(UserConfig config) throws Exception {
        String content = post("/config/user", mapper.writeValueAsString(config));
        return mapper.readValue(content, String.class);
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        Map<String, PluginConfig> pluginConfigs = getConfig().getPluginConfigs();
        if (pluginConfigs == null) {
            return null;
        }
        return pluginConfigs.get(pluginId);
    }

    // returns new version
    public String updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        String content = post("/config/plugin/" + pluginId, mapper.writeValueAsString(config));
        return mapper.readValue(content, String.class);
    }

    public List<PointcutConfig> getPointcutConfigs() throws Exception {
        return getConfig().getPointcutConfigs();
    }

    // returns new version
    public String addPointcutConfig(PointcutConfig pointcutConfig) throws Exception {
        String content = post("/config/pointcut/+", mapper.writeValueAsString(pointcutConfig));
        return mapper.readValue(content, String.class);
    }

    // returns new version
    public String updatePointcutConfig(String version, PointcutConfig pointcutConfig)
            throws Exception {
        String content =
                post("/config/pointcut/" + version, mapper.writeValueAsString(pointcutConfig));
        return mapper.readValue(content, String.class);
    }

    public void removePointcutConfig(String version) throws Exception {
        post("/config/pointcut/-", mapper.writeValueAsString(version));
    }

    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    public void compactData() throws Exception {
        post("/admin/data/compact", "");
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTraceSummary(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, true);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, false);
    }

    public void cleanUpAfterEachTest() throws Exception {
        post("/admin/data/delete-all", "");
        assertNoActiveTraces();
        // TODO assert no warn or error log messages
        post("/admin/config/reset-all", "");
    }

    public int getNumPendingCompleteTraces() throws Exception {
        String numPendingCompleteTraces = get("/admin/num-pending-complete-traces");
        return Integer.parseInt(numPendingCompleteTraces);
    }

    public long getNumStoredTraceSnapshots() throws Exception {
        String numStoredTraceSnapshots = get("/admin/num-stored-trace-snapshots");
        return Long.parseLong(numStoredTraceSnapshots);
    }

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

    private Trace getLastTrace(boolean summary) throws Exception {
        String content = get("/explorer/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        List<RawPoint> points = Lists.newArrayList();
        points.addAll(response.getNormalPoints());
        points.addAll(response.getErrorPoints());
        if (points.isEmpty()) {
            throw new AssertionError("no trace found");
        }
        RawPoint mostRecentCapturedPoint = Collections.max(points, new Comparator<RawPoint>() {
            public int compare(RawPoint point1, RawPoint point2) {
                return Longs.compare(point1.getCapturedAt(), point2.getCapturedAt());
            }
        });
        if (summary) {
            String traceContent = get("/explorer/summary/" + mostRecentCapturedPoint.getId());
            Trace trace = mapper.readValue(traceContent, Trace.class);
            trace.setSummary(true);
            return trace;
        } else {
            String traceContent = get("/explorer/detail/" + mostRecentCapturedPoint.getId());
            return mapper.readValue(traceContent, Trace.class);
        }
    }

    @Nullable
    private Trace getActiveTrace(boolean summary) throws Exception {
        String content = get("/explorer/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE + "&limit=1000");
        TracePointResponse response = mapper.readValue(content, TracePointResponse.class);
        if (response.getActivePoints().isEmpty()) {
            return null;
        } else if (response.getActivePoints().size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            RawPoint point = response.getActivePoints().get(0);
            if (summary) {
                String traceContent = get("/explorer/summary/" + point.getId());
                Trace trace = mapper.readValue(traceContent, Trace.class);
                trace.setSummary(true);
                return trace;
            } else {
                String traceContent = get("/explorer/detail/" + point.getId());
                return mapper.readValue(traceContent, Trace.class);
            }
        }
    }

    private Config getConfig() throws Exception {
        return mapper.readValue(get("/config/read"), Config.class);
    }

    private void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTraces = Integer.parseInt(get("/admin/num-active-traces"));
            if (numActiveTraces == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }

    private String get(String path) throws Exception {
        BoundRequestBuilder request = asyncHttpClient.prepareGet("http://localhost:" + uiPort
                + path);
        Response response = request.execute().get();
        return validateAndReturnBody(response);
    }

    private String post(String path, String data) throws Exception {
        BoundRequestBuilder request = asyncHttpClient.preparePost("http://localhost:" + uiPort
                + path);
        request.setBody(data);
        Response response = request.execute().get();
        return validateAndReturnBody(response);
    }

    private static String validateAndReturnBody(Response response) throws IOException {
        if (wasUncompressed(response)) {
            throw new IllegalStateException("HTTP response was not compressed");
        }
        if (response.getStatusCode() == HttpResponseStatus.OK.getCode()) {
            return response.getResponseBody();
        } else {
            throw new IllegalStateException("Unexpected HTTP status code returned: "
                    + response.getStatusCode() + " (" + response.getStatusText() + ")");
        }
    }

    private static InputStream validateAndReturnBodyAsStream(Response response) throws IOException {
        if (wasUncompressed(response)) {
            throw new IllegalStateException("HTTP response was not compressed");
        }
        if (response.getStatusCode() == HttpResponseStatus.OK.getCode()) {
            return response.getResponseBodyAsStream();
        } else {
            throw new IllegalStateException("Unexpected HTTP status code returned: "
                    + response.getStatusCode() + " (" + response.getStatusText() + ")");
        }
    }

    // this method relies on io.informant.testkit.InformantContainer.SaveTheEncodingHandler
    // being inserted into the Netty pipeline before the decompression handler (which removes the
    // Content-Encoding header after decompression) so that the original Content-Encoding can be
    // still be retrieved via the alternate http header X-Original-Content-Encoding
    private static boolean wasUncompressed(Response response) throws AssertionError {
        String contentLength = response.getHeader("Content-Length");
        if ("0".equals(contentLength)) {
            // zero-length responses are never compressed
            return false;
        }
        String contentEncoding = response.getHeader("X-Original-Content-Encoding");
        return !"gzip".equals(contentEncoding);
    }
}
