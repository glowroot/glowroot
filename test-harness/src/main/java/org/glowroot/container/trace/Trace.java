/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.trace;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import dataflow.quals.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.nullToFalse;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Trace {

    private final String id;
    private final boolean active;
    private final boolean stuck;
    private final long startTime;
    private final long captureTime;
    private final long duration;
    private final boolean background;
    private final String headline;
    private final String transactionName;
    @Nullable
    private final String error;
    @Nullable
    private final String user;
    private final ImmutableSetMultimap<String, String> attributes;
    private final ImmutableList<Metric> metrics;
    private final JvmInfo jvmInfo;
    @Nullable
    private final ImmutableList<Span> spans;
    @Nullable
    private final MergedStackTreeNode coarseMergedStackTree;
    @Nullable
    private final MergedStackTreeNode fineMergedStackTree;

    private final boolean summary;

    private Trace(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, boolean background, String headline, String transactionName,
            @Nullable String error, @Nullable String user,
            ImmutableSetMultimap<String, String> attributes, @ReadOnly List<Metric> metrics,
            JvmInfo jvmInfo, @ReadOnly @Nullable List<Span> spans,
            @Nullable MergedStackTreeNode coarseMergedStackTree,
            @Nullable MergedStackTreeNode fineMergedStackTree, boolean summary) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.background = background;
        this.headline = headline;
        this.transactionName = transactionName;
        this.error = error;
        this.user = user;
        this.attributes = attributes;
        this.metrics = ImmutableList.copyOf(metrics);
        this.jvmInfo = jvmInfo;
        this.spans = spans == null ? null : ImmutableList.copyOf(spans);
        this.coarseMergedStackTree = coarseMergedStackTree;
        this.fineMergedStackTree = fineMergedStackTree;
        this.summary = summary;
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

    public String getHeadline() {
        return headline;
    }

    public String getTransactionName() {
        return transactionName;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public ImmutableSetMultimap<String, String> getAttributes() {
        return attributes;
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

    @Nullable
    public ImmutableList<Span> getSpans() {
        if (summary) {
            throw new IllegalStateException("Use Glowroot.getLastTrace() instead of"
                    + " Glowroot.getLastTraceSummary() to retrieve spans");
        }
        return spans;
    }

    @Nullable
    public MergedStackTreeNode getCoarseMergedStackTree() {
        if (summary) {
            throw new IllegalStateException("Use Glowroot.getLastTrace() instead of"
                    + " Glowroot.getLastTraceSummary() to retrieve mergedStackTree");
        }
        return coarseMergedStackTree;
    }

    @Nullable
    public MergedStackTreeNode getFineMergedStackTree() {
        if (summary) {
            throw new IllegalStateException("Use Glowroot.getLastTrace() instead of"
                    + " Glowroot.getLastTraceSummary() to retrieve mergedStackTree");
        }
        return fineMergedStackTree;
    }

    // the glowroot weaving metric is a bit unpredictable since tests are often run inside the
    // same GlowrootContainer for test speed, so test order affects whether any classes are
    // woven during the test or not
    // it's easiest to just ignore this metric completely
    private ImmutableList<Metric> getStableAndOrderedMetrics() {
        List<Metric> stableMetrics = Lists.newArrayList(metrics);
        for (Iterator<Metric> i = stableMetrics.iterator(); i.hasNext();) {
            if ("glowroot weaving".equals(i.next().getName())) {
                i.remove();
            }
        }
        return ImmutableList.copyOf(Metric.orderingByTotal.reverse().sortedCopy(stableMetrics));
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("stuck", stuck)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("background", background)
                .add("headline", headline)
                .add("transactionName", transactionName)
                .add("error", error)
                .add("user", user)
                .add("attributes", attributes)
                .add("metrics", metrics)
                .add("jvmInfo", jvmInfo)
                .add("spans", spans)
                .add("coarseMergedStackTree", coarseMergedStackTree)
                .add("fineMergedStackTree", fineMergedStackTree)
                .add("summary", summary)
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
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("attributes") @Nullable Map<String, List<String>> attributes,
            @JsonProperty("metrics") @Nullable List<Metric> metrics,
            @JsonProperty("jvmInfo") @Nullable JvmInfo jvmInfo,
            @JsonProperty("spans") @Nullable List<Span> spans,
            @JsonProperty("coarseMergedStackTree") @Nullable MergedStackTreeNode coarseMergedStackTree,
            @JsonProperty("fineMergedStackTree") @Nullable MergedStackTreeNode fineMergedStackTree,
            @JsonProperty("summary") @Nullable Boolean summary)
            throws JsonMappingException {
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(stuck, "stuck");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(duration, "duration");
        checkRequiredProperty(background, "background");
        checkRequiredProperty(headline, "headline");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(metrics, "metrics");
        checkRequiredProperty(jvmInfo, "jvmInfo");
        ImmutableSetMultimap.Builder<String, String> theAttributes = ImmutableSetMultimap.builder();
        if (attributes != null) {
            for (Entry<String, List<String>> entry : attributes.entrySet()) {
                theAttributes.putAll(entry.getKey(), entry.getValue());
            }
        }
        return new Trace(id, active, stuck, startTime, captureTime, duration, background, headline,
                transactionName, error, user, theAttributes.build(), metrics, jvmInfo, spans,
                coarseMergedStackTree, fineMergedStackTree, nullToFalse(summary));
    }
}
