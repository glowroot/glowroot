/*
 * Copyright 2012-2014 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class TracePointResponse {

    private final ImmutableList<RawPoint> normalPoints;
    private final ImmutableList<RawPoint> errorPoints;
    private final ImmutableList<RawPoint> activePoints;

    private TracePointResponse(List<RawPoint> normalPoints, List<RawPoint> errorPoints,
            List<RawPoint> activePoints) {
        this.normalPoints = ImmutableList.copyOf(normalPoints);
        this.errorPoints = ImmutableList.copyOf(errorPoints);
        this.activePoints = ImmutableList.copyOf(activePoints);
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

    @JsonCreator
    static TracePointResponse readValue(
            @JsonProperty("normalPoints") @Nullable List<RawPoint> normalPoints,
            @JsonProperty("errorPoints") @Nullable List<RawPoint> errorPoints,
            @JsonProperty("activePoints") @Nullable List<RawPoint> activePoints)
            throws JsonMappingException {
        checkRequiredProperty(normalPoints, "normalPoints");
        checkRequiredProperty(errorPoints, "errorPoints");
        checkRequiredProperty(activePoints, "activePoints");
        return new TracePointResponse(normalPoints, errorPoints, activePoints);
    }

    @Immutable
    static class RawPoint {

        static final Ordering<RawPoint> orderingByCaptureTime = new Ordering<RawPoint>() {
            @Override
            public int compare(@Nullable RawPoint left, @Nullable RawPoint right) {
                checkNotNull(left);
                checkNotNull(right);
                return Longs.compare(left.captureTime, right.captureTime);
            }
        };

        private final long captureTime;
        private final String id;

        private RawPoint(long captureTime, String id) {
            this.captureTime = captureTime;
            this.id = id;
        }

        String getId() {
            return id;
        }

        @JsonCreator
        static RawPoint readValue(ArrayNode point) {
            long captureTime = point.get(0).asLong();
            // duration which is point.get(1) is not needed here
            String id = point.get(2).asText();
            return new RawPoint(captureTime, id);
        }
    }
}
