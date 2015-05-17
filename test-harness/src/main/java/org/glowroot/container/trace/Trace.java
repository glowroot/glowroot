/*
 * Copyright 2011-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;
import static org.glowroot.container.common.ObjectMappers.orEmpty;

public class Trace {

    private final String id;
    private final boolean active;
    private final boolean partial;
    private final boolean error;
    private final long startTime;
    private final long captureTime;
    private final long duration;
    private final String transactionType;
    private final String transactionName;
    private final String headline;
    private final @Nullable String user;
    private final ImmutableSetMultimap<String, String> customAttributes;
    private final Map<String, /*@Nullable*/Object> customDetail;
    private final @Nullable String errorMessage;
    private final @Nullable ThrowableInfo errorThrowable;
    private final Timer rootTimer;
    private final @Nullable Long threadCpuTime;
    private final @Nullable Long threadBlockedTime;
    private final @Nullable Long threadWaitedTime;
    private final @Nullable Long threadAllocatedBytes;
    private final ImmutableList<TraceGcInfo> gcInfos;
    private final long queryCount;
    private final long entryCount;
    private final long profileSampleCount;
    private final Existence queriesExistence;
    private final Existence entriesExistence;
    private final Existence profileExistence;

    private Trace(String id, boolean active, boolean partial, boolean error, long startTime,
            long captureTime, long duration, String transactionType, String transactionName,
            String headline, @Nullable String user,
            ImmutableSetMultimap<String, String> customAttributes,
            Map<String, /*@Nullable*/Object> customDetail, @Nullable String errorMessage,
            @Nullable ThrowableInfo errorThrowable, Timer rootTimer, @Nullable Long threadCpuTime,
            @Nullable Long threadBlockedTime, @Nullable Long threadWaitedTime,
            @Nullable Long threadAllocatedBytes, List<TraceGcInfo> gcInfos, long queryCount,
            long entryCount, long profileSampleCount, Existence queriesExistence,
            Existence entriesExistence, Existence profileExistence) {
        this.id = id;
        this.active = active;
        this.partial = partial;
        this.error = error;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.duration = duration;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.headline = headline;
        this.user = user;
        this.customAttributes = customAttributes;
        this.customDetail = customDetail;
        this.errorMessage = errorMessage;
        this.errorThrowable = errorThrowable;
        this.rootTimer = rootTimer;
        this.threadCpuTime = threadCpuTime;
        this.threadBlockedTime = threadBlockedTime;
        this.threadWaitedTime = threadWaitedTime;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.gcInfos = ImmutableList.copyOf(gcInfos);
        this.queryCount = queryCount;
        this.entryCount = entryCount;
        this.profileSampleCount = profileSampleCount;
        this.queriesExistence = queriesExistence;
        this.entriesExistence = entriesExistence;
        this.profileExistence = profileExistence;
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

    public boolean isError() {
        return error;
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

    public @Nullable String getUser() {
        return user;
    }

    public ImmutableSetMultimap<String, String> getCustomAttributes() {
        return customAttributes;
    }

    public Map<String, /*@Nullable*/Object> getCustomDetail() {
        return customDetail;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public @Nullable ThrowableInfo getErrorThrowable() {
        return errorThrowable;
    }

    public Timer getRootTimer() {
        return rootTimer;
    }

    public @Nullable Long getThreadCpuTime() {
        return threadCpuTime;
    }

    public @Nullable Long getThreadBlockedTime() {
        return threadBlockedTime;
    }

    public @Nullable Long getThreadWaitedTime() {
        return threadWaitedTime;
    }

    public @Nullable Long getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    public ImmutableList<TraceGcInfo> getGcInfos() {
        return gcInfos;
    }

    public long getQueryCount() {
        return queryCount;
    }

    public long getEntryCount() {
        return entryCount;
    }

    public long getProfileSampleCount() {
        return profileSampleCount;
    }

    public Existence getQueriesExistence() {
        return queriesExistence;
    }

    public Existence getEntriesExistence() {
        return entriesExistence;
    }

    public Existence getProfileExistence() {
        return profileExistence;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("active", active)
                .add("partial", partial)
                .add("error", error)
                .add("startTime", startTime)
                .add("captureTime", captureTime)
                .add("duration", duration)
                .add("transactionType", transactionType)
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("user", user)
                .add("customAttributes", customAttributes)
                .add("customDetail", customDetail)
                .add("errorMessage", errorMessage)
                .add("errorThrowable", errorThrowable)
                .add("rootTimer", rootTimer)
                .add("threadCpuTime", threadCpuTime)
                .add("threadBlockedTime", threadBlockedTime)
                .add("threadWaitedTime", threadWaitedTime)
                .add("threadAllocatedBytes", threadAllocatedBytes)
                .add("gcInfos", gcInfos)
                .add("queryCount", queryCount)
                .add("entryCount", entryCount)
                .add("profileSampleCount", profileSampleCount)
                .add("queriesExistence", queriesExistence)
                .add("entriesExistence", entriesExistence)
                .add("profileExistence", profileExistence)
                .toString();
    }

    @JsonCreator
    static Trace readValue(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("partial") @Nullable Boolean partial,
            @JsonProperty("error") @Nullable Boolean error,
            @JsonProperty("startTime") @Nullable Long startTime,
            @JsonProperty("captureTime") @Nullable Long captureTime,
            @JsonProperty("duration") @Nullable Long duration,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("customAttributes") @Nullable Map<String, /*@Nullable*/List</*@Nullable*/String>> customAttributes,
            @JsonProperty("customDetail") @Nullable Map<String, /*@Nullable*/Object> customDetail,
            @JsonProperty("errorMessage") @Nullable String errorMessage,
            @JsonProperty("errorThrowable") @Nullable ThrowableInfo errorThrowable,
            @JsonProperty("timers") @Nullable Timer rootTimer,
            @JsonProperty("threadCpuTime") @Nullable Long threadCpuTime,
            @JsonProperty("threadBlockedTime") @Nullable Long threadBlockedTime,
            @JsonProperty("threadWaitedTime") @Nullable Long threadWaitedTime,
            @JsonProperty("threadAllocatedBytes") @Nullable Long threadAllocatedBytes,
            @JsonProperty("gcInfos") @Nullable List</*@Nullable*/TraceGcInfo> gcInfosUnchecked,
            @JsonProperty("queryCount") @Nullable Long queryCount,
            @JsonProperty("entryCount") @Nullable Long entryCount,
            @JsonProperty("profileSampleCount") @Nullable Long profileSampleCount,
            @JsonProperty("queriesExistence") @Nullable Existence queriesExistence,
            @JsonProperty("entriesExistence") @Nullable Existence entriesExistence,
            @JsonProperty("profileExistence") @Nullable Existence profileExistence)
            throws JsonMappingException {
        List<TraceGcInfo> gcInfos = orEmpty(gcInfosUnchecked, "gcInfos");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(partial, "partial");
        checkRequiredProperty(error, "error");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(duration, "duration");
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(headline, "headline");
        checkRequiredProperty(rootTimer, "timers");
        checkRequiredProperty(queryCount, "queryCount");
        checkRequiredProperty(entryCount, "entryCount");
        checkRequiredProperty(profileSampleCount, "profileSampleCount");
        checkRequiredProperty(queriesExistence, "queriesExistence");
        checkRequiredProperty(entriesExistence, "entriesExistence");
        checkRequiredProperty(profileExistence, "profileExistence");
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
                List<String> values = orEmpty(uncheckedValues, "customAttributes");
                theCustomAttributes.putAll(entry.getKey(), values);
            }
        }
        return new Trace(id, active, partial, error, startTime, captureTime, duration,
                transactionType, transactionName, headline, user, theCustomAttributes.build(),
                nullToEmpty(customDetail), errorMessage, errorThrowable, rootTimer, threadCpuTime,
                threadBlockedTime, threadWaitedTime, threadAllocatedBytes, gcInfos, queryCount,
                entryCount, profileSampleCount, queriesExistence, entriesExistence,
                profileExistence);
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }
}
