/*
 * Copyright 2013-2015 the original author or authors.
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
import com.google.common.collect.ImmutableList;

import static org.glowroot.container.common.ObjectMappers.checkNotNullItems;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;
import static org.glowroot.container.common.ObjectMappers.nullToFalse;

public class TraceEntry {

    private final long offsetNanos;
    private final long durationNanos;
    private final boolean active;
    private final int nestingLevel;
    // messageText is null for entries created via TransactionService.addErrorEntry(ErrorMessage)
    private final @Nullable String messageText;
    private final Map<String, /*@Nullable*/Object> messageDetail;
    private final @Nullable String errorMessage;
    private final @Nullable ThrowableInfo errorThrowable;
    private final @Nullable ImmutableList<StackTraceElement> stackTrace;

    private TraceEntry(long offsetNanos, long durationNanos, boolean active, int nestingLevel,
            @Nullable String messageText, Map<String, /*@Nullable*/Object> messageDetail,
            @Nullable String errorMessage, @Nullable ThrowableInfo errorThrowable,
            @Nullable List<StackTraceElement> stackTrace) {
        this.offsetNanos = offsetNanos;
        this.durationNanos = durationNanos;
        this.active = active;
        this.nestingLevel = nestingLevel;
        this.messageText = messageText;
        this.messageDetail = messageDetail;
        this.errorMessage = errorMessage;
        this.errorThrowable = errorThrowable;
        this.stackTrace = stackTrace == null ? null : ImmutableList.copyOf(stackTrace);
    }

    public long getOffsetNanos() {
        return offsetNanos;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public boolean isActive() {
        return active;
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    public @Nullable String getMessageText() {
        return messageText;
    }

    public Map<String, /*@Nullable*/ Object> getMessageDetail() {
        return messageDetail;
    }

    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    public @Nullable ThrowableInfo getErrorThrowable() {
        return errorThrowable;
    }

    public @Nullable ImmutableList<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("offsetNanos", offsetNanos)
                .add("durationNanos", durationNanos)
                .add("active", active)
                .add("nestingLevel", nestingLevel)
                .add("messageText", messageText)
                .add("messageDetail", messageDetail)
                .add("errorMessage", errorMessage)
                .add("errorThrowable", errorThrowable)
                .add("stackTrace", stackTrace)
                .toString();
    }

    @JsonCreator
    static TraceEntry readValue(
            @JsonProperty("offsetNanos") @Nullable Long offsetNanos,
            @JsonProperty("durationNanos") @Nullable Long durationNanos,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("nestingLevel") @Nullable Integer nestingLevel,
            @JsonProperty("messageText") @Nullable String messageText,
            @JsonProperty("messageDetail") @Nullable Map<String, /*@Nullable*/Object> messageDetail,
            @JsonProperty("errorMessage") @Nullable String errorMessage,
            @JsonProperty("errorThrowable") @Nullable ThrowableInfo errorThrowable,
            @JsonProperty("stackTrace") @Nullable List</*@Nullable*/StackTraceElement> uncheckedStackTrace)
                    throws JsonMappingException {
        List<StackTraceElement> stackTrace = checkNotNullItems(uncheckedStackTrace, "stackTrace");
        return new TraceEntry(nullToZero(offsetNanos), nullToZero(durationNanos),
                nullToFalse(active), nullToZero(nestingLevel), messageText,
                nullToEmpty(messageDetail), errorMessage, errorThrowable, stackTrace);
    }

    private static long nullToZero(@Nullable Long value) {
        return value == null ? 0 : value;
    }

    private static int nullToZero(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
