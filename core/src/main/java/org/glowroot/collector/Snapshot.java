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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSetMultimap;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

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
    private final String transactionType;
    private final String transactionName;
    private final String headline;
    @Nullable
    private final String error;
    @Nullable
    private final String user;
    @Nullable
    private final String customAttributes; // json data
    @Nullable
    private final String metrics; // json data
    @Nullable
    private final String threadInfo; // json data
    @Nullable
    private final String gcInfos; // json data
    private final Existence spansExistence;
    private final Existence profileExistence;
    private final Existence outlierProfileExistence;
    @Nullable
    private final ImmutableSetMultimap<String, String> customAttributesForIndexing;

    private Snapshot(String id, boolean active, boolean stuck, long startTime, long captureTime,
            long duration, String transactionType, String transactionName, String headline,
            @Nullable String error, @Nullable String user, @Nullable String customAttributes,
            @Nullable String metrics, @Nullable String threadInfo, @Nullable String gcInfos,
            Existence spansExistence, Existence profileExistence,
            Existence outlierProfileExistence,
            @Nullable ImmutableSetMultimap<String, String> customAttributesForIndexing) {
        this.id = id;
        this.active = active;
        this.stuck = stuck;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.headline = headline;
        this.error = error;
        this.user = user;
        this.customAttributes = customAttributes;
        this.metrics = metrics;
        this.threadInfo = threadInfo;
        this.gcInfos = gcInfos;
        this.spansExistence = spansExistence;
        this.profileExistence = profileExistence;
        this.outlierProfileExistence = outlierProfileExistence;
        this.customAttributesForIndexing = customAttributesForIndexing;
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

    public String getTransactionType() {
        return transactionType;
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
    public String getCustomAttributes() {
        return customAttributes;
    }

    @Nullable
    public String getMetrics() {
        return metrics;
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

    Existence getOutlierProfileExistence() {
        return outlierProfileExistence;
    }

    Existence getProfileExistence() {
        return profileExistence;
    }

    @Nullable
    public ImmutableSetMultimap<String, String> getCustomAttributesForIndexing() {
        return customAttributesForIndexing;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("stuck", stuck)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("transactionType", transactionType)
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("error", error)
                .add("user", user)
                .add("customAttributes", customAttributes)
                .add("metrics", metrics)
                .add("threadInfo", threadInfo)
                .add("gcInfos", gcInfos)
                .add("spansExistence", spansExistence)
                .add("outlierProfileExistence", outlierProfileExistence)
                .add("profileExistence", profileExistence)
                .add("customAttributesForIndexing", customAttributesForIndexing)
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
        @MonotonicNonNull
        private String transactionType;
        @MonotonicNonNull
        private String transactionName;
        @MonotonicNonNull
        private String headline;
        @Nullable
        private String error;
        @Nullable
        private String user;
        @Nullable
        private String customAttributes;
        @Nullable
        private String metrics;
        @Nullable
        private String threadInfo;
        @Nullable
        private String gcInfos;
        @MonotonicNonNull
        private Existence spansExistence;
        @MonotonicNonNull
        private Existence profileExistence;
        @MonotonicNonNull
        private Existence outlierProfileExistence;
        @Nullable
        private ImmutableSetMultimap<String, String> customAttributesForIndexing;

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

        @EnsuresNonNull("transactionType")
        public Builder transactionType(String transactionType) {
            this.transactionType = transactionType;
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

        public Builder customAttributes(@Nullable String customAttributes) {
            this.customAttributes = customAttributes;
            return this;
        }

        public Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
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

        @EnsuresNonNull("profileExistence")
        public Builder profileExistence(Existence profileExistence) {
            this.profileExistence = profileExistence;
            return this;
        }

        @EnsuresNonNull("outlierProfileExistence")
        public Builder outlierProfileExistence(Existence outlierProfileExistence) {
            this.outlierProfileExistence = outlierProfileExistence;
            return this;
        }

        public Builder customAttributesForIndexing(
                ImmutableSetMultimap<String, String> attributesForIndexing) {
            this.customAttributesForIndexing = attributesForIndexing;
            return this;
        }

        @RequiresNonNull({"id", "transactionType", "transactionName", "headline", "spansExistence",
                "profileExistence", "outlierProfileExistence"})
        public Snapshot build() {
            return new Snapshot(id, active, stuck, startTime, captureTime, duration,
                    transactionType, transactionName, headline, error, user, customAttributes,
                    metrics, threadInfo, gcInfos, spansExistence, profileExistence,
                    outlierProfileExistence, customAttributesForIndexing);
        }
    }
}
