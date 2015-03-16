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
package org.glowroot.transaction.model;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.common.Tickers;

import static com.google.common.base.Preconditions.checkNotNull;

// this supports updating by a single thread and reading by multiple threads
public class TraceEntry implements org.glowroot.api.TraceEntry {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntry.class);
    private static final Ticker ticker = Tickers.getTicker();

    private static final TraceEntry limitExceededMarker = new TraceEntry(null, 0, 0, null);

    private static final TraceEntry limitExtendedMarker = new TraceEntry(null, 0, 0, null);

    private final @Nullable MessageSupplier messageSupplier;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ErrorMessage errorMessage;

    private final long startTick;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private boolean completed;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private long endTick;

    private final int nestingLevel;

    // only null for limitExceededMarker and limitExtendedMarker
    private final @Nullable TimerImpl timer;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ImmutableList<StackTraceElement> stackTrace;

    TraceEntry(@Nullable MessageSupplier messageSupplier, long startTick, int nesting,
            @Nullable TimerImpl timer) {
        this.messageSupplier = messageSupplier;
        this.startTick = startTick;
        this.nestingLevel = nesting;
        this.timer = timer;
    }

    @Override
    public @Nullable MessageSupplier getMessageSupplier() {
        return messageSupplier;
    }

    public @Nullable ReadableErrorMessage getErrorMessage() {
        return (ReadableErrorMessage) errorMessage;
    }

    public long getStartTick() {
        return startTick;
    }

    public boolean isCompleted() {
        return completed;
    }

    public long getEndTick() {
        return endTick;
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    public @Nullable ImmutableList<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    public boolean isLimitExceededMarker() {
        return this == limitExceededMarker;
    }

    public boolean isLimitExtendedMarker() {
        return this == limitExtendedMarker;
    }

    @Override
    public void end() {
        endInternal(ticker.read(), null);
    }

    @Override
    public void endWithStackTrace(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
            end();
            return;
        }
        long endTick = ticker.read();
        if (endTick - startTick >= unit.toNanos(threshold)) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back a few stack calls:
            // skip i=0 which is "java.lang.Thread.getStackTrace()"
            // skip i=1 which is "...TraceEntry.endWithStackTrace()"
            // skip i=2 which is the plugin advice
            this.stackTrace = ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length);
        }
        endInternal(endTick, null);
    }

    @Override
    public void endWithError(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("endWithError(): argument 'errorMessage' must be non-null");
            // fallback to end() without error
            end();
            return;
        }
        endInternal(ticker.read(), errorMessage);
    }

    private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
        // timer is only null for limitExceededMarker, limitExtendedMarker and trace entries added
        // using addEntry()
        checkNotNull(timer);
        Transaction transaction = timer.getTransaction();
        timer.end(endTick);
        transaction.popEntry(this, endTick, errorMessage);
    }

    @Nullable
    TimerImpl getTimer() {
        return timer;
    }

    void setErrorMessage(@Nullable ErrorMessage errorMessage) {
        this.errorMessage = errorMessage;
    }

    void setEndTick(long endTick) {
        this.endTick = endTick;
        this.completed = true;
    }

    public void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    static TraceEntry getLimitExceededMarker() {
        return limitExceededMarker;
    }

    static TraceEntry getLimitExtendedMarker() {
        return limitExtendedMarker;
    }
}
