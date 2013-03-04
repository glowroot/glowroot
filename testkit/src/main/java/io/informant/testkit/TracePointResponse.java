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
package io.informant.testkit;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TracePointResponse {

    private final List<RawPoint> normalPoints;
    private final List<RawPoint> errorPoints;
    private final List<RawPoint> activePoints;

    @JsonCreator
    TracePointResponse(@JsonProperty("normalPoints") List<RawPoint> normalPoints,
            @JsonProperty("errorPoints") List<RawPoint> errorPoints,
            @JsonProperty("activePoints") List<RawPoint> activePoints) {
        this.normalPoints = normalPoints;
        this.errorPoints = errorPoints;
        this.activePoints = activePoints;
    }

    List<RawPoint> getNormalPoints() {
        return normalPoints;
    }

    List<RawPoint> getErrorPoints() {
        return errorPoints;
    }

    List<RawPoint> getActivePoints() {
        return activePoints;
    }

    static class RawPoint implements Comparable<RawPoint> {
        private final long capturedAt;
        private final double durationSeconds;
        private final String id;
        @JsonCreator
        static RawPoint from(ArrayNode point) {
            long capturedAt = point.get(0).asLong();
            double durationSeconds = point.get(1).asDouble();
            String id = point.get(2).asText();
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
        public int compareTo(RawPoint o) {
            return Double.compare(o.durationSeconds, durationSeconds);
        }
    }
}
