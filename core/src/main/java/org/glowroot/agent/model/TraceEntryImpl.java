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
package org.glowroot.agent.model;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.common.model.DetailMapWriter;
import org.glowroot.common.util.Tickers;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.QueryEntry;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.internal.ReadableMessage;

import static com.google.common.base.Preconditions.checkNotNull;

// this supports updating by a single thread and reading by multiple threads
public class TraceEntryImpl extends QueryEntryBase implements QueryEntry, Timer {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntryImpl.class);
    private static final Ticker ticker = Tickers.getTicker();

    private final @Nullable TraceEntryImpl parentTraceEntry;
    private final @Nullable MessageSupplier messageSupplier;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ErrorMessage errorMessage;

    private final long startTick;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private long revisedStartTick;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private int selfNestingLevel;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private long endTick;

    // this is for maintaining linear list of trace entries
    private @Nullable TraceEntryImpl nextTraceEntry;

    // only null for trace entries added using addEntryEntry()
    private final @Nullable TimerImpl timer;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ImmutableList<StackTraceElement> stackTrace;

    // only used by transaction thread
    private long stackTraceThreshold;
    // only used by transaction thread
    private @MonotonicNonNull TimerImpl extendedTimer;

    TraceEntryImpl(@Nullable TraceEntryImpl parentTraceEntry,
            @Nullable MessageSupplier messageSupplier, @Nullable QueryData queryData,
            long queryExecutionCount, long startTick, @Nullable TimerImpl timer) {
        super(queryData);
        this.parentTraceEntry = parentTraceEntry;
        this.messageSupplier = messageSupplier;
        this.startTick = startTick;
        this.timer = timer;
        revisedStartTick = startTick;
        selfNestingLevel = 1;
        if (queryData != null) {
            queryData.start(startTick, queryExecutionCount);
        }
    }

    @Override
    public @Nullable MessageSupplier getMessageSupplier() {
        return messageSupplier;
    }

    @Nullable
    ErrorMessage getErrorMessage() {
        return errorMessage;
    }

    Trace.Entry toProtobuf(long transactionStartTick, long captureTick,
            List<Trace.Entry> childEntries) {
        long offsetNanos = startTick - transactionStartTick;
        long durationNanos;
        boolean active;
        if (isCompleted() && Tickers.lessThanOrEqual(endTick, captureTick)) {
            // total time is calculated relative to revised start tick
            durationNanos = endTick - revisedStartTick;
            active = false;
        } else {
            // total time is calculated relative to revised start tick
            durationNanos = captureTick - revisedStartTick;
            active = true;
        }
        MessageSupplier messageSupplier = getMessageSupplier();
        ReadableMessage message =
                messageSupplier == null ? null : (ReadableMessage) messageSupplier.get();

        Trace.Entry.Builder builder = Trace.Entry.newBuilder()
                .setStartOffsetNanos(offsetNanos)
                .setDurationNanos(durationNanos)
                .setActive(active)
                .setMessage(message == null ? "" : message.getText() + getRowCountSuffix());
        if (message != null) {
            builder.addAllDetailEntry(DetailMapWriter.toProtobufDetail(message.getDetail()));
        }
        ErrorMessage errorMessage = this.errorMessage;
        if (errorMessage != null) {
            Trace.Error.Builder errorBuilder = builder.getErrorBuilder();
            errorBuilder.setMessage(errorMessage.message());
            Trace.Throwable throwable = errorMessage.throwable();
            if (throwable != null) {
                errorBuilder.setException(throwable);
            }
            errorBuilder.build();
        }
        if (stackTrace != null) {
            for (StackTraceElement stackTraceElement : stackTrace) {
                builder.addLocationStackTraceElementBuilder()
                        .setClassName(stackTraceElement.getClassName())
                        .setMethodName(Strings.nullToEmpty(stackTraceElement.getMethodName()))
                        .setFileName(Strings.nullToEmpty(stackTraceElement.getFileName()))
                        .setLineNumber(stackTraceElement.getLineNumber())
                        .build();
            }
        }
        builder.addAllChildEntry(childEntries);
        return builder.build();
    }

    long getStartTick() {
        return startTick;
    }

    private boolean isCompleted() {
        return selfNestingLevel == 0;
    }

    @Override
    public void end() {
        long endTick = ticker.read();
        endInternal(endTick, null);
    }

    @Override
    public void endWithStackTrace(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
            end();
            return;
        }
        long endTick = ticker.read();
        long thresholdNanos = unit.toNanos(threshold);
        if (endTick - startTick >= thresholdNanos) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back a few stack calls:
            // skip i=0 which is "java.lang.Thread.getStackTrace()"
            // skip i=1 which is "...TraceEntry.endWithStackTrace()"
            // skip i=2 which is the plugin advice
            this.stackTrace = ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length);
        } else {
            // store threshold in case this trace entry is extended, see extend() below
            stackTraceThreshold = thresholdNanos;
        }
        endInternal(endTick, null);
    }

    @Override
    public void endWithError(Throwable t) {
        endWithErrorInternal(ErrorMessage.from(t));
    }

    @Override
    public void endWithError(@Nullable String message) {
        endWithErrorInternal(ErrorMessage.from(message));
    }

    @Override
    public void endWithError(@Nullable String message, Throwable t) {
        endWithErrorInternal(ErrorMessage.from(message, t));
    }

    @Override
    public Timer extend() {
        // timer is only null for trace entries added using addEntryEntry(), and these trace entries
        // are not returned from plugin api so no way for extend() to be called when timer is null
        checkNotNull(timer);
        if (selfNestingLevel++ == 0) {
            long priorDurationNanos = endTick - revisedStartTick;
            long currTick = ticker.read();
            revisedStartTick = currTick - priorDurationNanos;
            extendedTimer = timer.extend(currTick);
            extendQueryData(currTick);
        }
        return this;
    }

    // this is called for stopping an extension
    @Override
    public void stop() {
        // the timer interface for this class is only expose through return value of extend()
        if (--selfNestingLevel == 0) {
            endTick = ticker.read();
            checkNotNull(extendedTimer);
            extendedTimer.end(endTick);
            endQueryData(endTick);
            if (stackTrace == null && stackTraceThreshold != 0
                    && endTick - revisedStartTick >= stackTraceThreshold) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // need to strip back a few stack calls:
                // skip i=0 which is "java.lang.Thread.getStackTrace()"
                // skip i=1 which is "...Timer.stop()"
                // skip i=2 which is the plugin advice
                this.stackTrace = ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length);
            }
        }
    }

    public void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Nullable
    TraceEntryImpl getParentTraceEntry() {
        return parentTraceEntry;
    }

    @Nullable
    TraceEntryImpl getNextTraceEntry() {
        return nextTraceEntry;
    }

    void setNextTraceEntry(TraceEntryImpl nextTraceEntry) {
        this.nextTraceEntry = nextTraceEntry;
    }

    void setErrorMessage(@Nullable ErrorMessage errorMessage) {
        this.errorMessage = errorMessage;
    }

    void setEndTick(long endTick) {
        this.endTick = endTick;
        this.selfNestingLevel--;
    }

    private void endWithErrorInternal(ErrorMessage errorMessage) {
        endInternal(ticker.read(), errorMessage);
        if (errorMessage.throwable() == null) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // need to strip back a few stack calls:
            // skip i=0 which is "java.lang.Thread.getStackTrace()"
            // skip i=1 which is "...TraceEntryImpl.endWithErrorInternal()"
            // skip i=2 which is "...TraceEntryImpl.endWithError()"
            // skip i=3 which is the plugin advice
            setStackTrace(ImmutableList.copyOf(stackTrace).subList(4, stackTrace.length));
        }
    }

    private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
        // timer is only null for trace entries added using addEntryEntry(), and these trace entries
        // are not returned from plugin api so no way for end...() to be called
        checkNotNull(timer);
        Transaction transaction = timer.getTransaction();
        timer.end(endTick);
        endQueryData(endTick);
        setErrorMessage(errorMessage);
        setEndTick(endTick);
        transaction.popEntry(this, endTick);
    }

    private String getRowCountSuffix() {
        if (!isQueryNavigationAttempted()) {
            return "";
        }
        long rowCount = getRowCount();
        if (rowCount == 1) {
            return " => 1 row";
        } else {
            return " => " + rowCount + " rows";
        }
    }
}
