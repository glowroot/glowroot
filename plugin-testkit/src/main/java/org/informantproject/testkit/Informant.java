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

import org.informantproject.testkit.Config.CoreProperties;
import org.informantproject.testkit.Config.PluginConfig;
import org.informantproject.testkit.Config.PluginConfigJsonDeserializer;
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

    public void setThresholdMillis(int thresholdMillis) throws Exception {
        CoreProperties coreProperties = getCoreProperties();
        coreProperties.setThresholdMillis(thresholdMillis);
        updateCoreProperties(coreProperties);
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

    public CoreProperties getCoreProperties() throws Exception {
        return getConfig().getCoreProperties();
    }

    public void updateCoreProperties(CoreProperties properties) throws Exception {
        post("/config/core/properties",
                new GsonBuilder().serializeNulls().create().toJson(properties));
    }

    public void disableCore() throws Exception {
        get("/config/core/disable");
    }

    public void enableCore() throws Exception {
        get("/config/core/enable");
    }

    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig().getPluginConfigs().get(pluginId);
    }

    public void storePluginProperties(String pluginId, String propertiesJson) throws Exception {
        post("/config/plugin/" + pluginId + "/properties", propertiesJson);
    }

    public void disablePlugin(String pluginId) throws Exception {
        get("/config/plugin/" + pluginId + "/disable");
    }

    public void enablePlugin(String pluginId) throws Exception {
        get("/config/plugin/" + pluginId + "/enable");
    }

    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    private Trace getLastTrace(boolean summary) throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE);
        JsonArray points = gson.fromJson(pointsJson, JsonElement.class).getAsJsonObject()
                .get("snapshotPoints").getAsJsonArray();
        if (points.size() == 0) {
            throw new AssertionError("no trace found");
        } else {
            JsonArray values = points.get(points.size() - 1).getAsJsonArray();
            String traceId = values.get(2).getAsString();
            String url = summary ? "/trace/summary/" : "/trace/detail/";
            String traceJson = get(url + traceId);
            return gson.fromJson(traceJson, Trace.class);
        }
    }

    @Nullable
    public Trace getActiveTrace() throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE);
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

    @Nullable
    public List<String> getStackTrace(String stackTraceHash) throws Exception {
        String stackTraceJson = get("/stacktrace/" + stackTraceHash);
        return gson.fromJson(stackTraceJson, new TypeToken<List<String>>() {}.getType());
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
