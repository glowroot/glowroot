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

import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.Configuration.PluginConfiguration;
import org.informantproject.testkit.Configuration.PluginConfigurationJsonDeserializer;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Informant {

    private final int uiPort;
    private final AsyncHttpClient asyncHttpClient;

    private long baselineTime;

    Informant(int uiPort, AsyncHttpClient asyncHttpClient) {
        this.uiPort = uiPort;
        this.asyncHttpClient = asyncHttpClient;
    }

    public void setThresholdMillis(int thresholdMillis) throws Exception {
        CoreConfiguration coreConfiguration = getCoreConfiguration();
        coreConfiguration.setThresholdMillis(thresholdMillis);
        updateCoreConfiguration(coreConfiguration);
    }

    public String get(String path) throws Exception {
        BoundRequestBuilder request = asyncHttpClient.prepareGet("http://localhost:" + uiPort
                + path);
        Response response = request.execute().get();
        return validateAndReturnBody(response);
    }

    public String post(String path, String data) throws Exception {
        BoundRequestBuilder request = asyncHttpClient.preparePost("http://localhost:" + uiPort
                + path);
        request.setBody(data);
        Response response = request.execute().get();
        return validateAndReturnBody(response);
    }

    public CoreConfiguration getCoreConfiguration() throws Exception {
        return getConfiguration().getCoreConfiguration();
    }

    public void updateCoreConfiguration(CoreConfiguration coreConfiguration) throws Exception {
        post("/configuration/core/properties", new GsonBuilder().serializeNulls().create().toJson(
                coreConfiguration));
    }

    public PluginConfiguration getPluginConfiguration(String pluginId) throws Exception {
        return getConfiguration().getPluginConfiguration().get(pluginId);
    }

    public void storePluginConfiguration(String pluginId, PluginConfiguration pluginConfiguration)
            throws Exception {

        post("/configuration/plugin/" + pluginId + "/properties", pluginConfiguration
                .getPropertiesJson());
    }

    public Trace getLastTrace() throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE);
        JsonArray points = new Gson().fromJson(pointsJson, JsonElement.class).getAsJsonObject()
                .get("storedTracePoints").getAsJsonArray();
        if (points.size() == 0) {
            return null;
        } else {
            JsonArray values = points.get(points.size() - 1).getAsJsonArray();
            String traceId = values.get(2).getAsString();
            String traceDetailJson = get("/trace/detail/" + traceId);
            return new Gson().fromJson(traceDetailJson, Trace.class);
        }
    }

    public Trace getActiveTrace() throws Exception {
        String pointsJson = get("/trace/points?from=0&to=" + Long.MAX_VALUE + "&low=0&high="
                + Long.MAX_VALUE);
        JsonArray points = new Gson().fromJson(pointsJson, JsonElement.class).getAsJsonObject()
                .get("activeTracePoints").getAsJsonArray();
        if (points.size() == 0) {
            return null;
        } else if (points.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            JsonArray values = points.get(0).getAsJsonArray();
            String traceId = values.get(2).getAsString();
            String traceDetailJson = get("/trace/detail/" + traceId);
            return new Gson().fromJson(traceDetailJson, Trace.class);
        }
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

    private Configuration getConfiguration() throws Exception {
        String json = get("/configuration/read");
        Gson gson = new GsonBuilder().registerTypeAdapter(PluginConfiguration.class,
                new PluginConfigurationJsonDeserializer()).create();
        return gson.fromJson(json, Configuration.class);
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
