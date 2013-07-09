/*
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

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;
import static io.informant.container.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonIgnoreProperties({"coarseMergedStackTree", "fineMergedStackTree"})
public class Trace {

    private final String id;
    private final boolean active;
    private final boolean stuck;
    private final long startTime;
    private final long captureTime;
    private final long duration;
    private final boolean background;
    private final String grouping;
    private final ImmutableMap<String, String> attributes;
    @Nullable
    private final String userId;
    @Nullable
    private final TraceError error;
    private final ImmutableList<Metric> metrics;
    private final JvmInfo jvmInfo;
    private final ImmutableList<Span> spans;

    private Trace(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, boolean background, String grouping,
            @ReadOnly Map<String, String> attributes, @Nullable String userId,
            @Nullable TraceError error, @ReadOnly List<Metric> metrics, JvmInfo jvmInfo,
            @ReadOnly List<Span> spans) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.background = background;
        this.grouping = grouping;
        this.attributes = ImmutableMap.copyOf(attributes);
        this.userId = userId;
        this.error = error;
        this.metrics = ImmutableList.copyOf(metrics);
        this.jvmInfo = jvmInfo;
        this.spans = ImmutableList.copyOf(spans);
    }

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

    public String getGrouping() {
        return grouping;
    }

    public ImmutableMap<String, String> getAttributes() {
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

    public ImmutableList<Metric> getMetrics() {
        return getStableAndOrderedMetrics();
    }

    @JsonIgnore
    public ImmutableList<String> getMetricNames() {
        ImmutableList.Builder<String> stableMetricNames = ImmutableList.builder();
        for (Metric stableMetric : getStableAndOrderedMetrics()) {
            stableMetricNames.add(stableMetric.getName());
        }
        return stableMetricNames.build();
    }

    public JvmInfo getJvmInfo() {
        return jvmInfo;
    }

    public ImmutableList<Span> getSpans() {
        return spans;
    }

    // the informant weaving metric is a bit unpredictable since tests are often run inside the
    // same InformantContainer for test speed, so test order affects whether any classes are
    // woven during the test or not
    // it's easiest to just ignore this metric completely
    private ImmutableList<Metric> getStableAndOrderedMetrics() {
        List<Metric> stableMetrics = Lists.newArrayList(metrics);
        for (Iterator<Metric> i = stableMetrics.iterator(); i.hasNext();) {
            if ("informant weaving".equals(i.next().getName())) {
                i.remove();
            }
        }
        return ImmutableList.copyOf(Metric.orderingByTotal.reverse().sortedCopy(stableMetrics));
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

    @JsonCreator
    static Trace readValue(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("stuck") @Nullable Boolean stuck,
            @JsonProperty("startTime") @Nullable Long startTime,
            @JsonProperty("captureTime") @Nullable Long captureTime,
            @JsonProperty("duration") @Nullable Long duration,
            @JsonProperty("background") @Nullable Boolean background,
            @JsonProperty("grouping") @Nullable String grouping,
            @JsonProperty("attributes") @Nullable Map<String, String> attributes,
            @JsonProperty("userId") @Nullable String userId,
            @JsonProperty("error") @Nullable TraceError error,
            @JsonProperty("metrics") @Nullable List<Metric> metrics,
            @JsonProperty("jvmInfo") @Nullable JvmInfo jvmInfo,
            @JsonProperty("spans") @Nullable List<Span> spans)
            throws JsonMappingException {
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(stuck, "stuck");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(duration, "duration");
        checkRequiredProperty(background, "background");
        checkRequiredProperty(grouping, "grouping");
        checkRequiredProperty(metrics, "metrics");
        checkRequiredProperty(jvmInfo, "jvmInfo");
        checkRequiredProperty(spans, "spans");
        return new Trace(id, active, stuck, startTime, captureTime, duration, background, grouping,
                nullToEmpty(attributes), userId, error, metrics, jvmInfo, spans);
    }
}
