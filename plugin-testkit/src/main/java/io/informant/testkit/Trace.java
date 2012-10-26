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
package io.informant.testkit;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Trace {

    private String id;
    private long from;
    private long to;
    private long duration;
    // active is slightly different from !completed because a trace is stored at the stuck threshold
    // and the jvm may terminate before that trace completes, in which case the trace is
    // neither active nor completed
    private boolean active;
    private boolean stuck;
    private boolean completed;
    private String description;
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

    public String getId() {
        return id;
    }

    public long getFrom() {
        return from;
    }

    public long getTo() {
        return to;
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

    public String getDescription() {
        return description;
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
        if (metrics == null) {
            return null;
        }
        // the informant weaving metric is a bit unpredictable since tests are often run inside the
        // same InformantContainer for test speed, so test order affects whether any classes are
        // woven during the test or not
        // it's easiest to just ignore this metric completely
        List<Metric> stableMetrics = Lists.newArrayList(metrics);
        for (Iterator<Metric> i = stableMetrics.iterator(); i.hasNext();) {
            if (i.next().getName().equals("informant weaving")) {
                i.remove();
            }
        }
        return stableMetrics;
    }

    @Nullable
    public List<String> getMetricNames() {
        List<Metric> metrics = getMetrics();
        if (metrics == null) {
            return null;
        }
        return Lists.transform(metrics, new Function<Metric, String>() {
            public String apply(@Nullable Metric metric) {
                return metric.getName();
            }
        });
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

    void setSummary(boolean summary) {
        this.summary = summary;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("from", from)
                .add("to", to)
                .add("duration", duration)
                .add("active", active)
                .add("stuck", stuck)
                .add("completed", completed)
                .add("description", description)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("error", error)
                .add("metrics", metrics)
                .add("spans", spans)
                .add("coarseMergedStackTree", coarseMergedStackTree)
                .add("fineMergedStackTree", fineMergedStackTree)
                .toString();
    }

    public static class TraceError {

        private String text;
        @Nullable
        private Map<String, Object> detail;
        @Nullable
        private CapturedException exception;

        public String getText() {
            return text;
        }
        @Nullable
        public Map<String, Object> getDetail() {
            return detail;
        }
        @Nullable
        public CapturedException getException() {
            return exception;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("text", text)
                    .add("detail", detail)
                    .add("exception", exception)
                    .toString();
        }
    }

    public static class CapturedException {

        private String display;
        private List<String> stackTrace;
        private int framesInCommonWithCaused;
        @Nullable
        private CapturedException cause;

        public String getDisplay() {
            return display;
        }
        public List<String> getStackTrace() {
            return stackTrace;
        }
        public int getFramesInCommonWithCaused() {
            return framesInCommonWithCaused;
        }
        public CapturedException getCause() {
            return cause;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("display", display)
                    .add("stackTrace", stackTrace)
                    .add("framesInCommonWithCaused", framesInCommonWithCaused)
                    .add("cause", cause)
                    .toString();
        }
    }

    public static class Metric {

        private String name;
        private long total;
        private long min;
        private long max;
        private long count;
        private boolean active;
        private boolean minActive;
        private boolean maxActive;

        public String getName() {
            return name;
        }
        public long getTotal() {
            return total;
        }
        public long getMin() {
            return min;
        }
        public long getMax() {
            return max;
        }
        public long getCount() {
            return count;
        }
        public boolean isActive() {
            return active;
        }
        public boolean isMinActive() {
            return minActive;
        }
        public boolean isMaxActive() {
            return maxActive;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("name", name)
                    .add("total", total)
                    .add("min", min)
                    .add("max", max)
                    .add("count", count)
                    .add("isActive", active)
                    .add("minActive", minActive)
                    .add("maxActive", maxActive)
                    .toString();
        }
    }

    public static class Span {

        private long offset;
        private long duration;
        private int index;
        private int parentIndex;
        private int nesting;
        // message is null for spans created via PluginServices.addErrorSpan()
        @Nullable
        private Message message;
        @Nullable
        private ErrorMessage error;
        @Nullable
        private List<String> stackTrace;

        public long getOffset() {
            return offset;
        }
        public long getDuration() {
            return duration;
        }
        public int getIndex() {
            return index;
        }
        public int getParentIndex() {
            return parentIndex;
        }
        public int getNesting() {
            return nesting;
        }
        @Nullable
        public Message getMessage() {
            return message;
        }
        @Nullable
        public ErrorMessage getError() {
            return error;
        }
        @Nullable
        public List<String> getStackTrace() {
            return stackTrace;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("offset", offset)
                    .add("duration", duration)
                    .add("index", index)
                    .add("parentIndex", parentIndex)
                    .add("nesting", nesting)
                    .add("message", message)
                    .add("error", error)
                    .add("stackTrace", stackTrace)
                    .toString();
        }
    }

    public static class Message {

        private String text;
        @Nullable
        private Map<String, Object> detail;

        public String getText() {
            return text;
        }
        @Nullable
        public Map<String, Object> getDetail() {
            return detail;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("text", text)
                    .add("detail", detail)
                    .toString();
        }
    }

    public static class ErrorMessage extends Message {

        @Nullable
        private CapturedException exception;

        @Nullable
        public CapturedException getException() {
            return exception;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("text", getText())
                    .add("detail", getDetail())
                    .add("exception", exception)
                    .toString();
        }
    }

    public static class MergedStackTreeNode {

        @Nullable
        private String stackTraceElement;
        @Nullable
        private List<MergedStackTreeNode> childNodes;
        private int sampleCount;
        @Nullable
        private Map<String, Integer> leafThreadStateSampleCounts;
        @Nullable
        private String singleLeafState;

        // null for synthetic root only
        @Nullable
        public String getStackTraceElement() {
            return stackTraceElement;
        }
        @Nullable
        public List<MergedStackTreeNode> getChildNodes() {
            return childNodes;
        }
        public int getSampleCount() {
            return sampleCount;
        }
        @Nullable
        public Map<String, Integer> getLeafThreadStateSampleCounts() {
            return leafThreadStateSampleCounts;
        }
        @Nullable
        public String getSingleLeafState() {
            return singleLeafState;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("stackTraceElement", stackTraceElement)
                    .add("childNodes", childNodes)
                    .add("sampleCount", sampleCount)
                    .add("leafThreadStateSampleCounts", leafThreadStateSampleCounts)
                    .add("singleLeafState", singleLeafState)
                    .toString();
        }
    }
}
