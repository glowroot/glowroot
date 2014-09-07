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
package org.glowroot.transaction.model;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.common.Ticker;
import org.glowroot.markers.GuardedBy;

/**
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class TraceEntryComponent {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntryComponent.class);

    // activeEntries doesn't need to be thread safe since only accessed by the transaction thread
    private final List<TraceEntry> activeEntries = Lists.newArrayList();

    private final long startTick;
    // not volatile, so depends on memory barrier in Trace for visibility
    @MonotonicNonNull
    private Long endTick;

    private final TraceEntry rootTraceEntry;
    // very little contention on entries, so synchronized ArrayList performs better than
    // ConcurrentLinkedQueue
    @GuardedBy("entries")
    private final List<TraceEntry> entries = Lists.newArrayList();

    // this doesn't need to be volatile since it is only accessed by the transaction thread
    private boolean entryLimitExceeded;

    private final Ticker ticker;

    TraceEntryComponent(MessageSupplier messageSupplier, TransactionMetricExt transactionMetric,
            long startTick, Ticker ticker) {
        this.startTick = startTick;
        this.ticker = ticker;
        rootTraceEntry = new TraceEntry(messageSupplier, startTick, 0, transactionMetric);
        activeEntries.add(rootTraceEntry);
        synchronized (entries) {
            entries.add(rootTraceEntry);
        }
    }

    TraceEntry getRootTraceEntry() {
        return rootTraceEntry;
    }

    ImmutableList<TraceEntry> getEntriesCopy() {
        synchronized (entries) {
            return ImmutableList.copyOf(entries);
        }
    }

    int getSize() {
        synchronized (entries) {
            return entries.size();
        }
    }

    long getStartTick() {
        return startTick;
    }

    @Nullable
    Long getEndTick() {
        return endTick;
    }

    // duration of trace in nanoseconds
    long getDuration() {
        return endTick == null ? ticker.read() - startTick : endTick - startTick;
    }

    boolean isCompleted() {
        return endTick != null;
    }

    TraceEntry pushEntry(long startTick, MessageSupplier messageSupplier,
            TransactionMetricExt transactionMetric) {
        TraceEntry entry = createEntry(startTick, messageSupplier, null, transactionMetric, false);
        activeEntries.add(entry);
        synchronized (entries) {
            entries.add(entry);
        }
        return entry;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. an entry never being marked as complete)
    void popEntry(TraceEntry entry, long endTick, @Nullable ErrorMessage errorMessage) {
        entry.setErrorMessage(errorMessage);
        entry.setEndTick(endTick);
        popEntrySafe(entry);
        if (activeEntries.isEmpty()) {
            this.endTick = endTick;
        }
    }

    TraceEntry addEntry(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        TraceEntry entry =
                createEntry(startTick, messageSupplier, errorMessage, null, limitBypassed);
        synchronized (entries) {
            entries.add(entry);
        }
        entry.setEndTick(endTick);
        return entry;
    }

    void addEntryLimitExceededMarkerIfNeeded() {
        if (entryLimitExceeded) {
            return;
        }
        entryLimitExceeded = true;
        synchronized (entries) {
            entries.add(TraceEntry.getLimitExceededMarker());
        }
    }

    private TraceEntry createEntry(long startTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, @Nullable TransactionMetricExt transactionMetric,
            boolean limitBypassed) {
        if (entryLimitExceeded && !limitBypassed) {
            // just in case the entryLimit property is changed in the middle of a trace this resets
            // the flag so that it can be triggered again (and possibly then a second limit marker)
            entryLimitExceeded = false;
            // also a different marker ("limit extended") is placed in the entries so that the ui
            // can display this scenario sensibly
            synchronized (entries) {
                entries.add(TraceEntry.getLimitExtendedMarker());
            }
        }
        TraceEntry currentEntry = activeEntries.get(activeEntries.size() - 1);
        int nestingLevel;
        if (entryLimitExceeded && limitBypassed) {
            // limit bypassed entries have no proper nesting, so put them directly under the root
            nestingLevel = 1;
        } else {
            nestingLevel = currentEntry.getNestingLevel() + 1;
        }
        TraceEntry entry =
                new TraceEntry(messageSupplier, startTick, nestingLevel, transactionMetric);
        entry.setErrorMessage(errorMessage);
        return entry;
    }

    private void popEntrySafe(TraceEntry entry) {
        if (activeEntries.isEmpty()) {
            logger.error("entry stack is empty, cannot pop entry: {}", entry);
            return;
        }
        TraceEntry pop = activeEntries.remove(activeEntries.size() - 1);
        if (!pop.equals(entry)) {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            logger.error("found entry {} at top of stack when expecting entry {}", pop, entry);
            while (!activeEntries.isEmpty() && !pop.equals(entry)) {
                pop = activeEntries.remove(activeEntries.size() - 1);
            }
            if (activeEntries.isEmpty() && !pop.equals(entry)) {
                logger.error("popped entire stack, never found entry: {}", entry);
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("activeEntries", activeEntries)
                .add("startTick", startTick)
                .add("endTick", endTick)
                .add("rootTraceEntry", rootTraceEntry)
                .add("entries", getEntriesCopy())
                .toString();
    }
}
