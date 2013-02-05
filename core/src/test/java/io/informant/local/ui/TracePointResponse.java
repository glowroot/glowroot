/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TracePointResponse {

    private final List<TracePointResponse.RawPoint> normalPoints;
    private final List<TracePointResponse.RawPoint> errorPoints;
    private final List<TracePointResponse.RawPoint> activePoints;

    static TracePointResponse from(String responseJson) {
        JsonObject points = new Gson().fromJson(responseJson, JsonElement.class).getAsJsonObject();
        JsonArray normalPointsJson = points.get("normalPoints").getAsJsonArray();
        List<TracePointResponse.RawPoint> normalPoints = Lists.newArrayList();
        for (int i = 0; i < normalPointsJson.size(); i++) {
            JsonArray normalPointJson = normalPointsJson.get(i).getAsJsonArray();
            normalPoints.add(RawPoint.from(normalPointJson));
        }
        JsonArray errorPointsJson = points.get("errorPoints").getAsJsonArray();
        List<TracePointResponse.RawPoint> errorPoints = Lists.newArrayList();
        for (int i = 0; i < errorPointsJson.size(); i++) {
            JsonArray errorPointJson = errorPointsJson.get(i).getAsJsonArray();
            errorPoints.add(RawPoint.from(errorPointJson));
        }
        JsonArray activePointsJson = points.get("activePoints").getAsJsonArray();
        List<TracePointResponse.RawPoint> activePoints = Lists.newArrayList();
        for (int i = 0; i < activePointsJson.size(); i++) {
            JsonArray activePointJson = activePointsJson.get(i).getAsJsonArray();
            activePoints.add(RawPoint.from(activePointJson));
        }
        return new TracePointResponse(normalPoints, errorPoints, activePoints);
    }

    private TracePointResponse(List<TracePointResponse.RawPoint> normalPoints,
            List<TracePointResponse.RawPoint> errorPoints,
            List<TracePointResponse.RawPoint> activePoints) {
        this.normalPoints = normalPoints;
        this.errorPoints = errorPoints;
        this.activePoints = activePoints;
    }

    List<TracePointResponse.RawPoint> getNormalPoints() {
        return normalPoints;
    }

    List<TracePointResponse.RawPoint> getErrorPoints() {
        return errorPoints;
    }

    List<TracePointResponse.RawPoint> getActivePoints() {
        return activePoints;
    }

    static class RawPoint implements Comparable<TracePointResponse.RawPoint> {
        private final long capturedAt;
        private final double durationSeconds;
        private final String id;
        private static TracePointResponse.RawPoint from(JsonArray point) {
            long capturedAt = point.get(0).getAsLong();
            double durationSeconds = point.get(1).getAsDouble();
            String id = point.get(2).getAsString();
            return new RawPoint(capturedAt, durationSeconds, id);
        }
        private RawPoint(long capturedAt, double durationSeconds, String id) {
            this.capturedAt = capturedAt;
            this.durationSeconds = durationSeconds;
            this.id = id;
        }
        long getCapturedAt() {
            return capturedAt;
        }
        double getDurationSeconds() {
            return durationSeconds;
        }
        String getId() {
            return id;
        }
        // natural sort order is by duration desc
        public int compareTo(TracePointResponse.RawPoint o) {
            return Double.compare(o.durationSeconds, durationSeconds);
        }
    }
}
