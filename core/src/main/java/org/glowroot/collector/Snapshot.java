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
package org.glowroot.collector;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSetMultimap;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.markers.Immutable;

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
    private final String transactionName;
    private final String headline;
    @Nullable
    private final String error;
    @Nullable
    private final String user;
    @Nullable
    private final String attributes; // json data
    @Nullable
    private final String traceMetrics; // json data
    @Nullable
    private final String threadInfo; // json data
    @Nullable
    private final String gcInfos; // json data
    private final Existence spansExistence;
    private final Existence coarseProfileExistence;
    private final Existence fineProfileExistence;
    @Nullable
    private final ImmutableSetMultimap<String, String> attributesForIndexing;

    private Snapshot(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, boolean background, String transactionName, String headline,
            @Nullable String error, @Nullable String user, @Nullable String attributes,
            @Nullable String traceMetrics, @Nullable String threadInfo, @Nullable String gcInfos,
            Existence spansExistence, Existence coarseProfileExistence,
            Existence fineProfileExistence,
            @Nullable ImmutableSetMultimap<String, String> attributesForIndexing) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.background = background;
        this.transactionName = transactionName;
        this.headline = headline;
        this.error = error;
        this.user = user;
        this.attributes = attributes;
        this.traceMetrics = traceMetrics;
        this.threadInfo = threadInfo;
        this.gcInfos = gcInfos;
        this.spansExistence = spansExistence;
        this.coarseProfileExistence = coarseProfileExistence;
        this.fineProfileExistence = fineProfileExistence;
        this.attributesForIndexing = attributesForIndexing;
    }

    public String getId() {
        return id;
    }

    boolean isActive() {
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

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        return headline;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    @Nullable
    public String getAttributes() {
        return attributes;
    }

    @Nullable
    public String getTraceMetrics() {
        return traceMetrics;
    }

    @Nullable
    public String getThreadInfo() {
        return threadInfo;
    }

    @Nullable
    public String getGcInfos() {
        return gcInfos;
    }

    Existence getSpansExistence() {
        return spansExistence;
    }

    Existence getCoarseProfileExistence() {
        return coarseProfileExistence;
    }

    Existence getFineProfileExistence() {
        return fineProfileExistence;
    }

    @Nullable
    public ImmutableSetMultimap<String, String> getAttributesForIndexing() {
        return attributesForIndexing;
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
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("error", error)
                .add("user", user)
                .add("attributes", attributes)
                .add("traceMetrics", traceMetrics)
                .add("threadInfo", threadInfo)
                .add("gcInfos", gcInfos)
                .add("spansExistence", spansExistence)
                .add("coarseProfileExistence", coarseProfileExistence)
                .add("fineProfileExistence", fineProfileExistence)
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
        private String transactionName;
        @MonotonicNonNull
        private String headline;
        @Nullable
        private String error;
        @Nullable
        private String user;
        @Nullable
        private String attributes;
        @Nullable
        private String traceMetrics;
        @Nullable
        private String threadInfo;
        @Nullable
        private String gcInfos;
        @MonotonicNonNull
        private Existence spansExistence;
        @MonotonicNonNull
        private Existence coarseProfileExistence;
        @MonotonicNonNull
        private Existence fineProfileExistence;
        @Nullable
        private ImmutableSetMultimap<String, String> attributesForIndexing;

        private Builder() {}

        @EnsuresNonNull("id")
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        Builder active(boolean active) {
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

        @EnsuresNonNull("transactionName")
        public Builder transactionName(String transactionName) {
            this.transactionName = transactionName;
            return this;
        }

        @EnsuresNonNull("headline")
        public Builder headline(String headline) {
            this.headline = headline;
            return this;
        }

        public Builder error(@Nullable String error) {
            this.error = error;
            return this;
        }

        public Builder user(@Nullable String user) {
            this.user = user;
            return this;
        }

        public Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder traceMetrics(@Nullable String traceMetrics) {
            this.traceMetrics = traceMetrics;
            return this;
        }

        public Builder threadInfo(@Nullable String threadInfo) {
            this.threadInfo = threadInfo;
            return this;
        }

        public Builder gcInfos(@Nullable String gcInfos) {
            this.gcInfos = gcInfos;
            return this;
        }

        @EnsuresNonNull("spansExistence")
        public Builder spansExistence(Existence spansExistence) {
            this.spansExistence = spansExistence;
            return this;
        }

        @EnsuresNonNull("coarseProfileExistence")
        public Builder coarseProfileExistence(Existence coarseProfileExistence) {
            this.coarseProfileExistence = coarseProfileExistence;
            return this;
        }

        @EnsuresNonNull("fineProfileExistence")
        public Builder fineProfileExistence(Existence fineProfileExistence) {
            this.fineProfileExistence = fineProfileExistence;
            return this;
        }

        public Builder attributesForIndexing(
                ImmutableSetMultimap<String, String> attributesForIndexing) {
            this.attributesForIndexing = attributesForIndexing;
            return this;
        }

        @RequiresNonNull({"id", "transactionName", "headline", "spansExistence",
                "coarseProfileExistence", "fineProfileExistence"})
        public Snapshot build() {
            return new Snapshot(id, active, stuck, startTime, captureTime, duration, background,
                    transactionName, headline, error, user, attributes, traceMetrics, threadInfo,
                    gcInfos, spansExistence, coarseProfileExistence, fineProfileExistence,
                    attributesForIndexing);
        }
    }
}
