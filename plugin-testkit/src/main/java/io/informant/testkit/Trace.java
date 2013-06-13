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

import java.util.List;
import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import io.informant.container.trace.JvmInfo;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@JsonIgnoreProperties({"coarseMergedStackTree", "fineMergedStackTree"})
public class Trace {

    @Nullable
    private String id;
    private boolean active;
    private boolean stuck;
    private long startTime;
    private long captureTime;
    private long duration;
    private boolean background;
    @Nullable
    private String grouping;
    @Nullable
    private Map<String, String> attributes;
    @Nullable
    private String userId;
    @Nullable
    private TraceError error;
    @Nullable
    private List<Metric> metrics;
    @Nullable
    private JvmInfo jvmInfo;
    @Nullable
    private List<Span> spans;

    @Nullable
    public String getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isStuck() {
        return stuck;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isBackground() {
        return background;
    }

    @Nullable
    public String getGrouping() {
        return grouping;
    }

    @Nullable
    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    @Nullable
    public TraceError getError() {
        return error;
    }

    @Nullable
    public List<Metric> getMetrics() {
        return metrics;
    }

    @Nullable
    public List<String> getMetricNames() {
        if (metrics == null) {
            return null;
        }
        List<String> metricNames = Lists.newArrayList();
        for (Metric metric : metrics) {
            String name = metric.getName();
            if (name == null) {
                throw new IllegalStateException("Found metric with null name");
            }
            metricNames.add(name);
        }
        return metricNames;
    }

    @Nullable
    public JvmInfo getJvmInfo() {
        return jvmInfo;
    }

    @Nullable
    public List<Span> getSpans() {
        return spans;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("stuck", stuck)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("background", background)
                .add("grouping", grouping)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("error", error)
                .add("metrics", metrics)
                .add("jvmInfo", jvmInfo)
                .add("spans", spans)
                .toString();
    }
}
