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
package org.glowroot.trace.model;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;

/**
 * The "span" terminology is borrowed from <a
 * href="http://research.google.com/pubs/pub36356.html">Dapper</a>.
 * 
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Span {

    private static final Span limitExceededMarker = new Span(null, 0, 0, null);

    private static final Span limitExtendedMarker = new Span(null, 0, 0, null);

    @Nullable
    private final MessageSupplier messageSupplier;
    // not volatile, so depends on memory barrier in Trace for visibility
    @Nullable
    private ErrorMessage errorMessage;

    private final long startTick;
    // not volatile, so depends on memory barrier in Trace for visibility
    @Nullable
    private Long endTick;

    private final int nestingLevel;

    // associated trace metric, stored here so it can be accessed in PluginServices.endSpan(Span)
    @Nullable
    private final TraceMetricTimerExt traceMetricTimer;
    // not volatile, so depends on memory barrier in Trace for visibility
    @Nullable
    private ImmutableList<StackTraceElement> stackTrace;

    Span(@Nullable MessageSupplier messageSupplier, long startTick, int nesting,
            @Nullable TraceMetricTimerExt traceMetricTimer) {
        this.messageSupplier = messageSupplier;
        this.startTick = startTick;
        this.nestingLevel = nesting;
        this.traceMetricTimer = traceMetricTimer;
    }

    @Nullable
    public MessageSupplier getMessageSupplier() {
        return messageSupplier;
    }

    @Nullable
    public ReadableErrorMessage getErrorMessage() {
        return (ReadableErrorMessage) errorMessage;
    }

    public long getStartTick() {
        return startTick;
    }

    @Nullable
    public Long getEndTick() {
        return endTick;
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    @Nullable
    public ImmutableList<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    public boolean isLimitExceededMarker() {
        return this == limitExceededMarker;
    }

    public boolean isLimitExtendedMarker() {
        return this == limitExtendedMarker;
    }

    @Nullable
    TraceMetricTimerExt getTraceMetricTimer() {
        return traceMetricTimer;
    }

    void setErrorMessage(@Nullable ErrorMessage errorMessage) {
        this.errorMessage = errorMessage;
    }

    void setEndTick(long endTick) {
        this.endTick = endTick;
    }

    public void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("message", messageSupplier == null ? null : messageSupplier.get())
                .add("errorMessage", errorMessage)
                .add("startTick", startTick)
                .add("endTick", endTick)
                .add("nestingLevel", nestingLevel)
                .add("traceMetricTimer", traceMetricTimer)
                .add("stackTrace", stackTrace)
                .add("limitExceededMarker", isLimitExceededMarker())
                .add("limitExtendedMarker", isLimitExtendedMarker())
                .toString();
    }

    static Span getLimitExceededMarker() {
        return limitExceededMarker;
    }

    static Span getLimitExtendedMarker() {
        return limitExtendedMarker;
    }
}
