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
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Tickers;
import org.glowroot.plugin.api.transaction.ErrorMessage;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.QueryEntry;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.internal.ReadableErrorMessage;

import static com.google.common.base.Preconditions.checkNotNull;

// this supports updating by a single thread and reading by multiple threads
public class TraceEntryImpl implements QueryEntry, Timer {

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

    private final int nestingLevel;

    // this is for maintaining linear list of trace entries
    private @Nullable TraceEntryImpl nextTraceEntry;

    // only null for limitExceededMarker and limitExtendedMarker
    private final @Nullable TimerImpl timer;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private @Nullable ImmutableList<StackTraceElement> stackTrace;

    // only used by transaction thread
    private long stackTraceThreshold;
    // only used by transaction thread
    private @MonotonicNonNull TimerImpl extendedTimer;

    // queryData, currRow and maxRow are only used by query entries
    private final @Nullable QueryData queryData;
    // row numbers start at 1
    private long currRow = -1;
    private long maxRow;

    TraceEntryImpl(@Nullable TraceEntryImpl parentTraceEntry,
            @Nullable MessageSupplier messageSupplier, @Nullable QueryData queryData,
            long queryExecutionCount, long startTick, int nestingLevel, @Nullable TimerImpl timer) {
        this.parentTraceEntry = parentTraceEntry;
        this.messageSupplier = messageSupplier;
        this.queryData = queryData;
        this.startTick = startTick;
        this.nestingLevel = nestingLevel;
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

    public @Nullable ReadableErrorMessage getErrorMessage() {
        return (ReadableErrorMessage) errorMessage;
    }

    public long getStartTick() {
        return startTick;
    }

    public long getRevisedStartTick() {
        return revisedStartTick;
    }

    public boolean isCompleted() {
        return selfNestingLevel == 0;
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
        return false;
    }

    public boolean isLimitExtendedMarker() {
        return false;
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
    public void endWithError(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("endWithError(): argument 'errorMessage' must be non-null");
            // fallback to end() without error
            end();
            return;
        }
        endInternal(ticker.read(), errorMessage);
    }

    @Override
    public Timer extend() {
        // timer is only null for limitExceededMarker, limitExtendedMarker and trace entries added
        // using addEntry(), and none of these trace entries are returned from plugin api so
        // no way for extend() to be called when timer is null
        checkNotNull(timer);
        if (selfNestingLevel++ == 0) {
            long priorDuration = endTick - revisedStartTick;
            long currTick = ticker.read();
            revisedStartTick = currTick - priorDuration;
            extendedTimer = timer.extend(currTick);
            if (queryData != null) {
                queryData.extend(currTick);
            }
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
            if (queryData != null) {
                queryData.end(endTick);
            }
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

    // TODO deal with copy paste between here and DummyTraceEntryOrQuery
    @Override
    public void incrementCurrRow() {
        if (currRow == -1) {
            currRow = 1;
            maxRow = 1;
            if (queryData != null) {
                // queryData can be null here if the aggregated query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(1);
            }
        } else if (currRow == maxRow) {
            currRow++;
            maxRow = currRow;
            if (queryData != null) {
                // queryData can be null here if the query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(1);
            }
        } else {
            currRow++;
        }
    }

    // TODO deal with copy paste between here and DummyTraceEntryOrQuery
    @Override
    public void setCurrRow(long row) {
        if (row > maxRow) {
            if (queryData != null) {
                // queryData can be null here if the aggregated query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(row - maxRow);
            }
            maxRow = row;
        }
        currRow = row;
    }

    public void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    // row count -1 means no navigation has been attempted
    // row count 0 means that navigation has been attempted but there were 0 rows
    public boolean isQueryNavigationAttempted() {
        return currRow != -1;
    }

    public long getRowCount() {
        return maxRow;
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

    private void endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
        // timer is only null for limitExceededMarker, limitExtendedMarker and trace entries added
        // using addEntry(), and none of these trace entries are returned from plugin api so
        // no way for end...() to be called
        checkNotNull(timer);
        Transaction transaction = timer.getTransaction();
        timer.end(endTick);
        if (queryData != null) {
            queryData.end(endTick);
        }
        setErrorMessage(errorMessage);
        setEndTick(endTick);
        transaction.popEntry(this, endTick);
    }

    static TraceEntryImpl getLimitExceededMarker() {
        return new LimitExceededTraceEntry();
    }

    static TraceEntryImpl getLimitExtendedMarker() {
        return new LimitExtendedTraceEntry();
    }

    private static class LimitExceededTraceEntry extends TraceEntryImpl {
        private LimitExceededTraceEntry() {
            super(null, null, null, 0, 0, 0, null);
        }
        @Override
        public boolean isLimitExceededMarker() {
            return true;
        }
    }

    private static class LimitExtendedTraceEntry extends TraceEntryImpl {
        private LimitExtendedTraceEntry() {
            super(null, null, null, 0, 0, 0, null);
        }
        @Override
        public boolean isLimitExtendedMarker() {
            return true;
        }
    }
}
