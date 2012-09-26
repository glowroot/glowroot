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
package org.informantproject.testkit;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.testkit.Config.CoarseProfilingConfig;
import org.informantproject.testkit.Config.CoreConfig;
import org.informantproject.testkit.Config.FineProfilingConfig;
import org.informantproject.testkit.Config.PluginConfig;
import org.informantproject.testkit.Config.PluginConfigJsonDeserializer;
import org.informantproject.testkit.Config.UserTracingConfig;
import org.informantproject.testkit.Trace.CapturedException;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
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
public class Informant {

    private final int uiPort;
    private final AsyncHttpClient asyncHttpClient;
    private final Gson gson = new Gson();

    private long baselineTime;

    Informant(int uiPort, AsyncHttpClient asyncHttpClient) {
        this.uiPort = uiPort;
        this.asyncHttpClient = asyncHttpClient;
    }

    public void setPersistenceThresholdMillis(int persistenceThresholdMillis) throws Exception {
        CoreConfig coreConfig = getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(persistenceThresholdMillis);
        updateCoreConfig(coreConfig);
    }

    public String get(String path) throws Exception {
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

    public CoreConfig getCoreConfig() throws Exception {
        return getConfig().getCoreConfig();
    }

    public void disableCore() throws Exception {
        post("/config/core/disable", "");
    }

    public void enableCore() throws Exception {
        post("/config/core/enable", "");
    }

    public void updateCoreConfig(CoreConfig config) throws Exception {
        // need to serialize nulls since the /config service treats absence of attribute different
        // from null attribute (the former doesn't update the attribute, the latter sets the
        // attribute to null)
        post("/config/core", new GsonBuilder().serializeNulls().create().toJson(config));
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() throws Exception {
        return getConfig().getCoarseProfilingConfig();
    }

    public void disableCoarseProfiling() throws Exception {
        post("/config/profiling/coarse/disable", "");
    }

    public void enableCoarseProfiling() throws Exception {
        post("/config/profiling/coarse/enable", "");
    }

    public void updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        // need to serialize nulls since the /config service treats absence of attribute different
        // from null attribute (the former doesn't update the attribute, the latter sets the
        // attribute to null)
        post("/config/profiling/coarse", new GsonBuilder().serializeNulls().create()
                .toJson(config));
    }

    public FineProfilingConfig getFineProfilingConfig() throws Exception {
        return getConfig().getFineProfilingConfig();
    }

    public void disableFineProfiling() throws Exception {
        post("/config/profiling/fine/disable", "");
    }

    public void enableFineProfiling() throws Exception {
        post("/config/profiling/fine/enable", "");
    }

    public void updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        // need to serialize nulls since the /config service treats absence of attribute different
        // from null attribute (the former doesn't update the attribute, the latter sets the
        // attribute to null)
        post("/config/profiling/fine", new GsonBuilder().serializeNulls().create().toJson(config));
    }

    public UserTracingConfig getUserTracingConfig() throws Exception {
        return getConfig().getUserTracingConfig();
    }

    public void enableUserTracing() throws Exception {
        post("/config/tracing/user/enable", "");
    }

    public void disableUserTracing() throws Exception {
        post("/config/tracing/user/disable", "");
    }

    public void updateUserTracingConfig(UserTracingConfig config) throws Exception {
        // need to serialize nulls since the /config service treats absence of attribute different
        // from null attribute (the former doesn't update the attribute, the latter sets the
        // attribute to null)
        post("/config/tracing/user", new GsonBuilder().serializeNulls().create().toJson(config));
    }

    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig().getPluginConfigs().get(pluginId);
    }

    public void disablePlugin(String pluginId) throws Exception {
        post("/config/plugin/" + pluginId + "/disable", "");
    }

    public void enablePlugin(String pluginId) throws Exception {
        post("/config/plugin/" + pluginId + "/enable", "");
    }

    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        // need to serialize nulls since the /config service treats absence of attribute different
        // from null attribute (the former doesn't update the attribute, the latter sets the
        // attribute to null)
        post("/config/plugin/" + pluginId, config.toJson());
    }

    public String getDataDir() throws Exception {
        return getConfig().getDataDir();
    }

    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    public List<LogMessage> getLog() throws Exception {
        return gson.fromJson(get("/misc/log"), new TypeToken<List<LogMessage>>() {}.getType());
    }

    private Trace getLastTrace(boolean summary) throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE + "&limit=1000");
        JsonArray points = gson.fromJson(pointsJson, JsonElement.class).getAsJsonObject()
                .get("storedPoints").getAsJsonArray();
        if (points.size() == 0) {
            throw new AssertionError("no trace found");
        } else {
            JsonArray point = getMaxPointByCapturedAt(points);
            String traceId = point.get(2).getAsString();
            if (summary) {
                String traceJson = get("/trace/summary/" + traceId);
                Trace trace = gson.fromJson(traceJson, Trace.class);
                trace.setSummary(true);
                return trace;
            } else {
                String traceJson = get("/trace/detail/" + traceId);
                return gson.fromJson(traceJson, Trace.class);
            }
        }
    }

    private JsonArray getMaxPointByCapturedAt(JsonArray points) {
        long maxCapturedAt = 0;
        JsonArray maxPoint = null;
        for (int i = 0; i < points.size(); i++) {
            JsonArray point = points.get(i).getAsJsonArray();
            long time = point.get(0).getAsLong();
            if (time > maxCapturedAt) {
                maxCapturedAt = time;
                maxPoint = point;
            }
        }
        return maxPoint;
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeout) throws Exception {
        long startTick = System.currentTimeMillis();
        Trace trace = null;
        // try at least once (e.g. in case timeout == 0)
        while (true) {
            trace = getActiveTrace();
            if (trace != null || System.currentTimeMillis() - startTick >= timeout) {
                break;
            }
            Thread.sleep(20);
        }
        return trace;
    }

    @Nullable
    public List<String> getStackTrace(String stackTraceBlockId) throws Exception {
        String stackTraceJson = get("/block/" + stackTraceBlockId);
        return gson.fromJson(stackTraceJson, new TypeToken<List<String>>() {}.getType());
    }

    @Nullable
    public CapturedException getException(String exceptionBlockId) throws Exception {
        String exceptionJson = get("/block/" + exceptionBlockId);
        return gson.fromJson(exceptionJson, CapturedException.class);
    }

    public void deleteAllTraces() throws Exception {
        get("/misc/cleardata?keepMillis=0&compact=true");
    }

    public int getNumPendingTraceWrites() throws Exception {
        String numTraces = get("/misc/numPendingTraceWrites");
        return Integer.parseInt(numTraces);
    }

    void resetBaselineTime() throws InterruptedException {
        if (baselineTime != 0) {
            // guarantee that there is no possible overlap
            Thread.sleep(1);
        }
        this.baselineTime = System.currentTimeMillis();
    }

    private Trace getActiveTrace() throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE + "&limit=1000");
        JsonArray points = gson.fromJson(pointsJson, JsonElement.class).getAsJsonObject()
                .get("activePoints").getAsJsonArray();
        if (points.size() == 0) {
            return null;
        } else if (points.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            JsonArray values = points.get(0).getAsJsonArray();
            String traceId = values.get(2).getAsString();
            String traceDetailJson = get("/trace/summary/" + traceId);
            return gson.fromJson(traceDetailJson, Trace.class);
        }
    }

    private Config getConfig() throws Exception {
        String json = get("/config/read");
        Gson gson = new GsonBuilder().registerTypeAdapter(PluginConfig.class,
                new PluginConfigJsonDeserializer()).create();
        return gson.fromJson(json, Config.class);
    }

    private static String validateAndReturnBody(Response response) throws IOException {
        if (response.getStatusCode() == HttpResponseStatus.OK.getCode()) {
            return response.getResponseBody();
        } else {
            throw new IllegalStateException("Unexpected HTTP status code returned: "
                    + response.getStatusCode() + " (" + response.getStatusText() + ")");
        }
    }
}
