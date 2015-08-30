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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;
import static org.glowroot.container.common.ObjectMappers.orEmpty;
import static org.glowroot.container.common.ObjectMappers.orEmpty2;

public class Trace {

    private final String id;
    private final boolean active;
    private final boolean partial;
    private final boolean error;
    private final long startTime;
    private final long captureTime;
    private final long durationNanos;
    private final String transactionType;
    private final String transactionName;
    private final String headline;
    private final @Nullable String user;
    private final Map<String, List<String>> customAttributes;
    private final Map<String, /*@Nullable*/Object> customDetail;
    private final @Nullable String errorMessage;
    private final @Nullable ThrowableInfo errorThrowable;
    private final TimerNode rootTimer;
    private final long threadCpuNanos;
    private final long threadBlockedNanos;
    private final long threadWaitedNanos;
    private final long threadAllocatedBytes;
    private final Map<String, GarbageCollectionActivity> gcActivity;
    private final long entryCount;
    private final boolean entryLimitExceeded;
    private final Existence entriesExistence;
    private final long profileSampleCount;
    private final boolean profileLimitExceeded;
    private final Existence profileExistence;

    private Trace(String id, boolean active, boolean partial, boolean error, long startTime,
            long captureTime, long duration, String transactionType, String transactionName,
            String headline, @Nullable String user, Map<String, List<String>> customAttributes,
            Map<String, /*@Nullable*/Object> customDetail, @Nullable String errorMessage,
            @Nullable ThrowableInfo errorThrowable, TimerNode rootTimer, long threadCpuNanos,
            long threadBlockedNanos, long threadWaitedNanos, long threadAllocatedBytes,
            Map<String, GarbageCollectionActivity> gcActivity, long entryCount,
            boolean entryLimitExceeded, Existence entriesExistence, long profileSampleCount,
            boolean profileLimitExceeded, Existence profileExistence) {
        this.id = id;
        this.active = active;
        this.partial = partial;
        this.error = error;
        this.startTime = startTime;
        this.captureTime = captureTime;
        this.durationNanos = duration;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.headline = headline;
        this.user = user;
        this.customAttributes = customAttributes;
        this.customDetail = customDetail;
        this.errorMessage = errorMessage;
        this.errorThrowable = errorThrowable;
        this.rootTimer = rootTimer;
        this.threadCpuNanos = threadCpuNanos;
        this.threadBlockedNanos = threadBlockedNanos;
        this.threadWaitedNanos = threadWaitedNanos;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.gcActivity = gcActivity;
        this.entryCount = entryCount;
        this.entryLimitExceeded = entryLimitExceeded;
        this.entriesExistence = entriesExistence;
        this.profileSampleCount = profileSampleCount;
        this.profileLimitExceeded = profileLimitExceeded;
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

    public long getDurationNanos() {
        return durationNanos;
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

    public Map<String, List<String>> getCustomAttributes() {
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

    public TimerNode getRootTimer() {
        return rootTimer;
    }

    public long getThreadCpuNanos() {
        return threadCpuNanos;
    }

    public long getThreadBlockedNanos() {
        return threadBlockedNanos;
    }

    public long getThreadWaitedNanos() {
        return threadWaitedNanos;
    }

    public long getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    public Map<String, GarbageCollectionActivity> getGcActivity() {
        return gcActivity;
    }

    public long getEntryCount() {
        return entryCount;
    }

    public boolean isEntryLimitExceeded() {
        return entryLimitExceeded;
    }

    public Existence getEntriesExistence() {
        return entriesExistence;
    }

    public long getProfileSampleCount() {
        return profileSampleCount;
    }

    public boolean isProfileLimitExceeded() {
        return profileLimitExceeded;
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
                .add("durationNanos", durationNanos)
                .add("transactionType", transactionType)
                .add("transactionName", transactionName)
                .add("headline", headline)
                .add("user", user)
                .add("customAttributes", customAttributes)
                .add("customDetail", customDetail)
                .add("errorMessage", errorMessage)
                .add("errorThrowable", errorThrowable)
                .add("rootTimer", rootTimer)
                .add("threadCpuNanos", threadCpuNanos)
                .add("threadBlockedNanos", threadBlockedNanos)
                .add("threadWaitedNanos", threadWaitedNanos)
                .add("threadAllocatedBytes", threadAllocatedBytes)
                .add("gcActivity", gcActivity)
                .add("entryCount", entryCount)
                .add("entryLimitExceeded", entryLimitExceeded)
                .add("entriesExistence", entriesExistence)
                .add("profileSampleCount", profileSampleCount)
                .add("profileLimitExceeded", profileLimitExceeded)
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
            @JsonProperty("durationNanos") @Nullable Long durationNanos,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("customAttributes") @Nullable Map<String, /*@Nullable*/List</*@Nullable*/String>> customAttributesUnchecked,
            @JsonProperty("customDetail") @Nullable Map<String, /*@Nullable*/Object> customDetail,
            @JsonProperty("errorMessage") @Nullable String errorMessage,
            @JsonProperty("errorThrowable") @Nullable ThrowableInfo errorThrowable,
            @JsonProperty("rootTimer") @Nullable TimerNode rootTimer,
            @JsonProperty("threadCpuNanos") @Nullable Long threadCpuNanos,
            @JsonProperty("threadBlockedNanos") @Nullable Long threadBlockedNanos,
            @JsonProperty("threadWaitedNanos") @Nullable Long threadWaitedNanos,
            @JsonProperty("threadAllocatedBytes") @Nullable Long threadAllocatedBytes,
            @JsonProperty("gcActivity") @Nullable Map<String, /*@Nullable*/GarbageCollectionActivity> gcActivityUnchecked,
            @JsonProperty("entryCount") @Nullable Long entryCount,
            @JsonProperty("entryLimitExceeded") @Nullable Boolean entryLimitExceeded,
            @JsonProperty("entriesExistence") @Nullable Existence entriesExistence,
            @JsonProperty("profileSampleCount") @Nullable Long profileSampleCount,
            @JsonProperty("profileLimitExceeded") @Nullable Boolean profileLimitExceeded,
            @JsonProperty("profileExistence") @Nullable Existence profileExistence)
                    throws JsonMappingException {
        Map<String, GarbageCollectionActivity> gcActivity =
                orEmpty(gcActivityUnchecked, "gcActivity");
        Map<String, List<String>> customAttributes =
                orEmpty2(customAttributesUnchecked, "customAttributes");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(partial, "partial");
        checkRequiredProperty(error, "error");
        checkRequiredProperty(startTime, "startTime");
        checkRequiredProperty(captureTime, "captureTime");
        checkRequiredProperty(durationNanos, "durationNanos");
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(transactionName, "transactionName");
        checkRequiredProperty(headline, "headline");
        checkRequiredProperty(rootTimer, "rootTimer");
        checkRequiredProperty(threadCpuNanos, "threadCpuNanos");
        checkRequiredProperty(threadBlockedNanos, "threadBlockedNanos");
        checkRequiredProperty(threadWaitedNanos, "threadWaitedNanos");
        checkRequiredProperty(threadAllocatedBytes, "threadAllocatedBytes");
        checkRequiredProperty(entryCount, "entryCount");
        checkRequiredProperty(entryLimitExceeded, "entryLimitExceeded");
        checkRequiredProperty(entriesExistence, "entriesExistence");
        checkRequiredProperty(profileSampleCount, "profileSampleCount");
        checkRequiredProperty(profileLimitExceeded, "profileLimitExceeded");
        checkRequiredProperty(profileExistence, "profileExistence");
        return new Trace(id, active, partial, error, startTime, captureTime, durationNanos,
                transactionType, transactionName, headline, user, customAttributes,
                nullToEmpty(customDetail), errorMessage, errorThrowable, rootTimer, threadCpuNanos,
                threadBlockedNanos, threadWaitedNanos, threadAllocatedBytes, gcActivity, entryCount,
                entryLimitExceeded, entriesExistence, profileSampleCount, profileLimitExceeded,
                profileExistence);
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }
}
