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
package org.glowroot.collector;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.EnsuresNonNull;
import checkers.nullness.quals.MonotonicNonNull;
import checkers.nullness.quals.Nullable;
import checkers.nullness.quals.RequiresNonNull;
import com.google.common.base.Objects;
import com.google.common.io.CharSource;
import dataflow.quals.Pure;

/**
 * Structure used as part of the response to "/backend/trace/detail".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Snapshot {

    private final String id;
    private final boolean active;
    private final boolean stuck;
    private final long startTime;
    private final long captureTime;
    private final long duration; // nanoseconds
    private final boolean background;
    private final String grouping;
    @Nullable
    private final String attributes; // json data
    @Nullable
    private final String userId;
    @Nullable
    private final String errorText;
    @Nullable
    private final String errorDetail; // json data
    @Nullable
    private final String exception; // json data
    @Nullable
    private final String metrics; // json data
    @Nullable
    private final String jvmInfo; // json data
    // using CharSource so these potentially very large strings can be streamed without consuming
    // large amounts of memory
    @Nullable
    private final CharSource spans; // json data
    @Nullable
    private final CharSource coarseMergedStackTree; // json data
    @Nullable
    private final CharSource fineMergedStackTree; // json data

    private Snapshot(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, boolean background, String grouping, @Nullable String attributes,
            @Nullable String userId, @Nullable String errorText, @Nullable String errorDetail,
            @Nullable String exception, @Nullable String metrics, @Nullable String jvmInfo,
            @Immutable @Nullable CharSource spans,
            @Immutable @Nullable CharSource coarseMergedStackTree,
            @Immutable @Nullable CharSource fineMergedStackTree) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.background = background;
        this.grouping = grouping;
        this.attributes = attributes;
        this.userId = userId;
        this.errorText = errorText;
        this.errorDetail = errorDetail;
        this.exception = exception;
        this.metrics = metrics;
        this.jvmInfo = jvmInfo;
        this.spans = spans;
        this.coarseMergedStackTree = coarseMergedStackTree;
        this.fineMergedStackTree = fineMergedStackTree;
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

    @Nullable
    public String getAttributes() {
        return attributes;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    @Nullable
    public String getErrorText() {
        return errorText;
    }

    @Nullable
    public String getErrorDetail() {
        return errorDetail;
    }

    @Nullable
    public String getException() {
        return exception;
    }

    @Nullable
    public String getMetrics() {
        return metrics;
    }

    @Nullable
    public String getJvmInfo() {
        return jvmInfo;
    }

    @Immutable
    @Nullable
    public CharSource getSpans() {
        return spans;
    }

    @Immutable
    @Nullable
    public CharSource getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Immutable
    @Nullable
    public CharSource getFineMergedStackTree() {
        return fineMergedStackTree;
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
                .add("grouping", grouping)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("errorText", errorText)
                .add("errorDetail", errorDetail)
                .add("exception", exception)
                .add("metrics", metrics)
                .toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        @MonotonicNonNull
        private String id;
        private boolean active;
        private boolean stuck;
        private long startTime;
        private long captureTime;
        private long duration;
        private boolean background;
        @MonotonicNonNull
        private String grouping;
        @Nullable
        private String attributes;
        @Nullable
        private String userId;
        @Nullable
        private String errorText;
        @Nullable
        private String errorDetail;
        @Nullable
        private String exception;
        @Nullable
        private String metrics;
        @Nullable
        private String jvmInfo;
        @Immutable
        @Nullable
        private CharSource spans;
        @Immutable
        @Nullable
        private CharSource coarseMergedStackTree;
        @Immutable
        @Nullable
        private CharSource fineMergedStackTree;

        private Builder() {}

        @EnsuresNonNull("id")
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder stuck(boolean stuck) {
            this.stuck = stuck;
            return this;
        }

        public Builder startTime(long startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder captureTime(long captureTime) {
            this.captureTime = captureTime;
            return this;
        }

        public Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        @EnsuresNonNull("grouping")
        public Builder grouping(String grouping) {
            this.grouping = grouping;
            return this;
        }

        public Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder userId(@Nullable String userId) {
            this.userId = userId;
            return this;
        }

        public Builder errorText(@Nullable String errorText) {
            this.errorText = errorText;
            return this;
        }

        public Builder errorDetail(@Nullable String errorDetail) {
            this.errorDetail = errorDetail;
            return this;
        }

        public Builder exception(@Nullable String exception) {
            this.exception = exception;
            return this;
        }

        public Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder jvmInfo(@Nullable String jvmInfo) {
            this.jvmInfo = jvmInfo;
            return this;
        }

        public Builder spans(@Immutable @Nullable CharSource spans) {
            this.spans = spans;
            return this;
        }

        public Builder coarseMergedStackTree(
                @Immutable @Nullable CharSource coarseMergedStackTree) {
            this.coarseMergedStackTree = coarseMergedStackTree;
            return this;
        }

        public Builder fineMergedStackTree(@Immutable @Nullable CharSource fineMergedStackTree) {
            this.fineMergedStackTree = fineMergedStackTree;
            return this;
        }

        @RequiresNonNull({"id", "grouping"})
        public Snapshot build() {
            return new Snapshot(id, active, stuck, startTime, captureTime, duration, background,
                    grouping, attributes, userId, errorText, errorDetail, exception, metrics,
                    jvmInfo, spans, coarseMergedStackTree, fineMergedStackTree);
        }
    }
}
