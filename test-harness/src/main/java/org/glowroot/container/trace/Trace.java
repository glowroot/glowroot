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

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.glowroot.container.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;

public class Trace {

    private final String id;
    private final boolean active;
    private final boolean partial;
    private final long startTime;
    private final long captureTime;
    private final long duration;
    private final String transactionType;
    private final String transactionName;
    private final String headline;
    @Nullable
    private final String error;
    @Nullable
    private final String user;
    private final ImmutableSetMultimap<String, String> customAttributes;
    private final TraceMetric rootMetric;
    @Nullable
    private final TraceThreadInfo threadInfo;
    private final ImmutableList<TraceGcInfo> gcInfos;
    private final Existence entriesExistence;
    private final Existence profileExistence;
    private final Existence outlierProfileExistence;

    private Trace(String id, boolean active, boolean partial, long startTime, long captureTime,
            long duration, String transactionType, String transactionName, String headline,
            @Nullable String error, @Nullable String user,
            ImmutableSetMultimap<String, String> customAttributes, TraceMetric rootMetric,
            @Nullable TraceThreadInfo threadInfo, List<TraceGcInfo> gcInfos,
            Existence entriesExistence, Existence profileExistence,
            Existence outlierProfileExistence) {
        this.id = id;
        this.active = active;
        this.partial = partial;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.headline = headline;
        this.error = error;
        this.user = user;
        this.customAttributes = customAttributes;
        this.rootMetric = rootMetric;
        this.threadInfo = threadInfo;
        this.gcInfos = ImmutableList.copyOf(gcInfos);
        this.entriesExistence = entriesExistence;
        this.profileExistence = profileExistence;
        this.outlierProfileExistence = outlierProfileExistence;
    }

    public String getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPartial() {
        return partial;
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

    public ImmutableSetMultimap<String, String> getCustomAttributes() {
        return customAttributes;
    }

    public TraceMetric getRootMetric() {
        return rootMetric;
    }

    @Nullable
    public TraceThreadInfo getThreadInfo() {
        return threadInfo;
    }

    public ImmutableList<TraceGcInfo> getGcInfos() {
        return gcInfos;
    }

    public Existence getEntriesExistence() {
        return entriesExistence;
    }

    public Existence getProfileExistence() {
        return profileExistence;
    }

    public Existence getOutlierProfileExistence() {
        return outlierProfileExistence;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("partial", partial)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("transactionType", transactionType)
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("error", error)
                .add("user", user)
                .add("customAttributes", customAttributes)
                .add("rootMetric", rootMetric)
                .add("threadInfo", threadInfo)
                .add("gcInfos", gcInfos)
                .add("entriesExistence", entriesExistence)
                .add("profileExistence", profileExistence)
                .add("outlierProfileExistence", outlierProfileExistence)
                .toString();
    }

    @JsonCreator
    static Trace readValue(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("partial") @Nullable Boolean partial,
            @JsonProperty("startTime") @Nullable Long startTime,
            @JsonProperty("captureTime") @Nullable Long captureTime,
            @JsonProperty("duration") @Nullable Long duration,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("customAttributes") @Nullable Map<String, /*@Nullable*/List</*@Nullable*/String>> customAttributes,
            @JsonProperty("metrics") @Nullable TraceMetric rootMetric,
            @JsonProperty("threadInfo") @Nullable TraceThreadInfo threadInfo,
            @JsonProperty("gcInfos") @Nullable List</*@Nullable*/TraceGcInfo> gcInfosUnchecked,
            @JsonProperty("entriesExistence") @Nullable Existence entriesExistence,
            @JsonProperty("profileExistence") @Nullable Existence profileExistence,
            @JsonProperty("outlierProfileExistence") @Nullable Existence outlierProfileExistence)
            throws JsonMappingException {
        List<TraceGcInfo> gcInfos = checkNotNullItemsForProperty(gcInfosUnchecked, "gcInfos");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(partial, "partial");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(duration, "duration");
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(headline, "headline");
        checkRequiredProperty(rootMetric, "metrics");
        checkRequiredProperty(entriesExistence, "entriesExistence");
        checkRequiredProperty(profileExistence, "profileExistence");
        checkRequiredProperty(outlierProfileExistence, "outlierProfileExistence");
        ImmutableSetMultimap.Builder<String, String> theCustomAttributes =
                ImmutableSetMultimap.builder();
        if (customAttributes != null) {
            for (Entry<String, /*@Nullable*/List</*@Nullable*/String>> entry : customAttributes
                    .entrySet()) {
                List</*@Nullable*/String> uncheckedValues = entry.getValue();
                if (uncheckedValues == null) {
                    throw new JsonMappingException(
                            "Null value not allowed for custom attribute map value");
                }
                List<String> values =
                        checkNotNullItemsForProperty(uncheckedValues, "customAttributes");
                theCustomAttributes.putAll(entry.getKey(), values);
            }
        }
        return new Trace(id, active, partial, startTime, captureTime, duration, transactionType,
                transactionName, headline, error, user, theCustomAttributes.build(), rootMetric,
                threadInfo, nullToEmpty(gcInfos), entriesExistence, profileExistence,
                outlierProfileExistence);
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }
}
