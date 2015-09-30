/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.ArrayNode;

class TracePointResponse {

    private final List<RawPoint> normalPoints;
    private final List<RawPoint> errorPoints;
    private final List<RawPoint> activePoints;

    @JsonCreator
    private TracePointResponse(@JsonProperty("normalPoints") List<RawPoint> normalPoints,
            @JsonProperty("errorPoints") List<RawPoint> errorPoints,
            @JsonProperty("activePoints") List<RawPoint> activePoints) throws JsonMappingException {
        this.normalPoints = normalPoints;
        this.errorPoints = errorPoints;
        this.activePoints = activePoints;
    }

    List<RawPoint> normalPoints() {
        return normalPoints;
    }

    List<RawPoint> errorPoints() {
        return errorPoints;
    }

    List<RawPoint> activePoints() {
        return activePoints;
    }

    static class RawPoint implements Comparable<RawPoint> {

        private final long captureTime;
        private final double totalMillis;
        private final String id;

        @JsonCreator
        RawPoint(ArrayNode point) {
            captureTime = point.get(0).asLong();
            totalMillis = point.get(1).asDouble();
            id = point.get(2).asText();
        }

        long captureTime() {
            return captureTime;
        }

        double totalMillis() {
            return totalMillis;
        }

        String id() {
            return id;
        }

        // natural sort order is by total time desc
        @Override
        public int compareTo(RawPoint o) {
            return Double.compare(o.totalMillis, totalMillis);
        }
    }
}
