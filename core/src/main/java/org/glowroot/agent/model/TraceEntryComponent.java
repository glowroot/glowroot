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

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Ticker;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.TraceEntry;
import org.glowroot.common.util.Tickers;
import org.glowroot.plugin.api.transaction.MessageSupplier;

import static com.google.common.base.Preconditions.checkNotNull;

// this supports updating by a single thread and reading by multiple threads
class TraceEntryComponent {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntryComponent.class);

    private final long startTick;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private boolean completed;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private long endTick;

    private final TraceEntryImpl rootEntry;

    @Nullable
    private TraceEntryImpl activeEntry;

    private TraceEntryImpl tailEntry;

    // entries.size() is accessed a lot, but only by transaction thread, so storing size separately
    // so it can be accessed without synchronization
    private int entryCount;

    // this doesn't need to be volatile since it is only accessed by the transaction thread
    private boolean entryLimitExceeded;

    private final Ticker ticker;

    TraceEntryComponent(MessageSupplier messageSupplier, TimerImpl timer, long startTick,
            Ticker ticker) {
        this.startTick = startTick;
        this.ticker = ticker;
        rootEntry = new TraceEntryImpl(null, messageSupplier, null, 0, startTick, -1, timer);
        activeEntry = rootEntry;
        tailEntry = rootEntry;
    }

    TraceEntryImpl getRootEntry() {
        return rootEntry;
    }

    // this does not include root trace entry
    public Collection<TraceEntry> getEntries() {
        long captureTick;
        if (completed) {
            captureTick = endTick;
        } else {
            captureTick = ticker.read();
        }
        List<TraceEntryImpl> entries = Lists.newArrayListWithCapacity(entryCount);
        TraceEntryImpl curr = rootEntry.getNextTraceEntry();
        // filter out entries that started after the capture tick
        while (curr != null && Tickers.lessThanOrEqual(curr.getStartTick(), captureTick)) {
            entries.add(curr);
            curr = curr.getNextTraceEntry();
        }
        return toSpiTraceEntries(entries, startTick, captureTick);
    }

    // this does not include root trace entry
    int getEntryCount() {
        return entryCount;
    }

    long getStartTick() {
        return startTick;
    }

    boolean isCompleted() {
        return completed;
    }

    long getEndTick() {
        return endTick;
    }

    long getDurationNanos() {
        return completed ? endTick - startTick : ticker.read() - startTick;
    }

    TraceEntryImpl pushEntry(long startTick, MessageSupplier messageSupplier,
            @Nullable QueryData queryData, long queryExecutionCount, TimerImpl timer) {
        TraceEntryImpl entry = createEntry(startTick, messageSupplier, queryData,
                queryExecutionCount, null, timer, false);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        activeEntry = entry;
        entryCount++;
        return entry;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. an entry never being marked as complete)
    void popEntry(TraceEntryImpl entry, long endTick) {
        popEntrySafe(entry);
        if (activeEntry == null) {
            this.endTick = endTick;
            this.completed = true;
        }
    }

    TraceEntryImpl addEntry(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        TraceEntryImpl entry =
                createEntry(startTick, messageSupplier, null, 1, errorMessage, null, limitBypassed);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        entryCount++;
        entry.setEndTick(endTick);
        return entry;
    }

    void setEntryLimitExceeded() {
        entryLimitExceeded = true;
    }

    boolean isEntryLimitExceeded() {
        return entryLimitExceeded;
    }

    private TraceEntryImpl createEntry(long startTick, @Nullable MessageSupplier messageSupplier,
            @Nullable QueryData queryData, long queryExecutionCount,
            @Nullable ErrorMessage errorMessage, @Nullable TimerImpl timer, boolean limitBypassed) {
        if (entryLimitExceeded && !limitBypassed) {
            // just in case the entryLimit property is changed in the middle of a trace this resets
            // the flag so that it can be triggered again (and possibly then a second limit marker)
            entryLimitExceeded = false;
        }
        int nestingLevel;
        if (entryLimitExceeded && limitBypassed) {
            // limit bypassed entries have no proper nesting, so put them directly under the root
            nestingLevel = 1;
        } else {
            // activeEntry is only null when transaction is complete
            checkNotNull(activeEntry);
            nestingLevel = activeEntry.nestingLevel() + 1;
        }
        TraceEntryImpl entry = new TraceEntryImpl(activeEntry, messageSupplier, queryData,
                queryExecutionCount, startTick, nestingLevel, timer);
        entry.setErrorMessage(errorMessage);
        return entry;
    }

    private void popEntrySafe(TraceEntryImpl entry) {
        if (activeEntry == null) {
            logger.error("entry stack is empty, cannot pop entry: {}", entry);
            return;
        }
        if (activeEntry == entry) {
            activeEntry = activeEntry.getParentTraceEntry();
        } else {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            popEntryBailout(entry);
        }
    }

    // split typically unused path into separate method to not affect inlining budget
    private void popEntryBailout(TraceEntryImpl expectingEntry) {
        logger.error("found entry {} at top of stack when expecting entry {}", activeEntry,
                expectingEntry);
        while (activeEntry != null && activeEntry != expectingEntry) {
            activeEntry = activeEntry.getParentTraceEntry();
        }
        if (activeEntry != null) {
            // now perform pop
            activeEntry = activeEntry.getParentTraceEntry();
        } else {
            logger.error("popped entire stack, never found entry: {}", expectingEntry);
        }
    }

    public static Collection<TraceEntry> toSpiTraceEntries(Collection<TraceEntryImpl> entries,
            final long transactionStartTick, final long captureTick) {
        return Collections2.transform(entries, new Function<TraceEntryImpl, TraceEntry>() {
            @Override
            public TraceEntry apply(@Nullable TraceEntryImpl traceEntry) {
                checkNotNull(traceEntry);
                return traceEntry.toSpiTraceEntry(transactionStartTick, captureTick);
            }
        });
    }
}
