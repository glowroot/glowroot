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
package io.informant.container.trace;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Trace {

    @Nullable
    private String id;
    private long start;
    private long duration;
    // active is slightly different from !completed because a trace is stored at the stuck threshold
    // and the jvm may terminate before that trace completes, in which case the trace is
    // neither active nor completed
    private boolean active;
    private boolean stuck;
    private boolean completed;
    private boolean background;
    @Nullable
    private String headline;
    @Nullable
    private Map<String, String> attributes;
    @Nullable
    private String userId;
    @Nullable
    private TraceError error;
    @Nullable
    private List<Metric> metrics;
    @Nullable
    private List<Span> spans;
    @Nullable
    private MergedStackTreeNode coarseMergedStackTree;
    @Nullable
    private MergedStackTreeNode fineMergedStackTree;

    private boolean summary;

    @Nullable
    public String getId() {
        return id;
    }

    public long getStart() {
        return start;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isStuck() {
        return stuck;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isBackground() {
        return background;
    }

    @Nullable
    public String getHeadline() {
        return headline;
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
        return getStableAndOrderedMetrics();
    }

    @Nullable
    public List<String> getMetricNames() {
        List<Metric> stableMetrics = getStableAndOrderedMetrics();
        if (stableMetrics == null) {
            return null;
        }
        List<String> stableMetricNames = Lists.newArrayList();
        for (Metric stableMetric : stableMetrics) {
            String name = stableMetric.getName();
            if (name == null) {
                throw new IllegalStateException("Found metric with null name");
            }
            stableMetricNames.add(name);
        }
        return stableMetricNames;
    }

    @Nullable
    public List<Span> getSpans() {
        if (summary) {
            throw new IllegalStateException("Use Informant.getLastTrace() instead of"
                    + " Informant.getLastTraceSummary() to retrieve spans");
        }
        return spans;
    }

    @Nullable
    public MergedStackTreeNode getCoarseMergedStackTree() {
        if (summary) {
            throw new IllegalStateException("Use Informant.getLastTrace() instead of"
                    + " Informant.getLastTraceSummary() to retrieve mergedStackTree");
        }
        return coarseMergedStackTree;
    }

    @Nullable
    public MergedStackTreeNode getFineMergedStackTree() {
        if (summary) {
            throw new IllegalStateException("Use Informant.getLastTrace() instead of"
                    + " Informant.getLastTraceSummary() to retrieve mergedStackTree");
        }
        return fineMergedStackTree;
    }

    public void setSummary(boolean summary) {
        this.summary = summary;
    }

    // the informant weaving metric is a bit unpredictable since tests are often run inside the
    // same InformantContainer for test speed, so test order affects whether any classes are
    // woven during the test or not
    // it's easiest to just ignore this metric completely
    @Nullable
    private List<Metric> getStableAndOrderedMetrics() {
        if (metrics == null) {
            return null;
        }
        List<Metric> stableMetrics = Lists.newArrayList(metrics);
        for (Iterator<Metric> i = stableMetrics.iterator(); i.hasNext();) {
            if ("informant weaving".equals(i.next().getName())) {
                i.remove();
            }
        }
        return Metric.orderingByTotal.reverse().sortedCopy(stableMetrics);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("start", start)
                .add("duration", duration)
                .add("active", active)
                .add("stuck", stuck)
                .add("completed", completed)
                .add("background", background)
                .add("headline", headline)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("error", error)
                .add("metrics", metrics)
                .add("spans", spans)
                .add("coarseMergedStackTree", coarseMergedStackTree)
                .add("fineMergedStackTree", fineMergedStackTree)
                .toString();
    }
}
