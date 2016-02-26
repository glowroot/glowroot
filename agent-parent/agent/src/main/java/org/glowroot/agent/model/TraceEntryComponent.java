/*
 * Copyright 2011-2016 the original author or authors.
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
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.util.Tickers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

// this supports updating by a single thread and reading by multiple threads
class TraceEntryComponent {

    private static final Logger logger = LoggerFactory.getLogger(TraceEntryComponent.class);

    private final long startTick;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private boolean completed;
    // not volatile, so depends on memory barrier in Transaction for visibility
    private long endTick;

    private final ThreadContextImpl threadContext;

    private final TraceEntryImpl rootEntry;

    private @Nullable TraceEntryImpl activeEntry;

    private TraceEntryImpl tailEntry;

    private final Ticker ticker;

    TraceEntryComponent(ThreadContextImpl threadContext, MessageSupplier messageSupplier,
            TimerImpl timer, long startTick, Ticker ticker) {
        this.threadContext = threadContext;
        this.startTick = startTick;
        this.ticker = ticker;
        rootEntry = new TraceEntryImpl(threadContext, null, messageSupplier, null, 0, startTick,
                timer, null);
        activeEntry = rootEntry;
        tailEntry = rootEntry;
    }

    TraceEntryImpl getRootEntry() {
        return rootEntry;
    }

    // this does not include the root trace entry
    public List<Trace.Entry> toProto(long captureTick,
            Multimap<TraceEntryImpl, TraceEntryImpl> auxRootTraceEntries) {
        if (captureTick < startTick) {
            return ImmutableList.of();
        }
        boolean completed = this.completed;
        if (completed && endTick < captureTick) {
            completed = false;
        }
        ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap = ArrayListMultimap.create();
        TraceEntryImpl entry = rootEntry.getNextTraceEntry();
        // filter out entries that started after the capture tick
        // checking completed is short circuit optimization for the common case
        while (entry != null
                && (completed || Tickers.lessThanOrEqual(entry.getStartTick(), captureTick))) {
            TraceEntryImpl parentTraceEntry = entry.getParentTraceEntry();
            if (parentTraceEntry == null) {
                logFoundNonRootEntryWithNullParent(entry);
                continue;
            }
            parentChildMap.put(parentTraceEntry, entry);
            entry = entry.getNextTraceEntry();
        }
        // merge in aux trace entry roots
        for (Entry<TraceEntryImpl, Collection<TraceEntryImpl>> entries : auxRootTraceEntries
                .asMap().entrySet()) {
            TraceEntryImpl parentTraceEntry = entries.getKey();
            List<TraceEntryImpl> childTraceEntries =
                    Lists.newArrayList(parentChildMap.get(parentTraceEntry));
            for (TraceEntryImpl auxRootTraceEntry : entries.getValue()) {
                TraceEntryImpl loopEntry = auxRootTraceEntry;
                while (loopEntry != null && (completed
                        || Tickers.lessThanOrEqual(loopEntry.getStartTick(), captureTick))) {
                    TraceEntryImpl loopParentEntry = loopEntry.getParentTraceEntry();
                    if (loopParentEntry == null) {
                        childTraceEntries.add(loopEntry);
                    } else {
                        parentChildMap.put(loopParentEntry, loopEntry);
                    }
                    loopEntry = loopEntry.getNextTraceEntry();
                }
            }
            childTraceEntries = TraceEntryImpl.orderingByStartTick.sortedCopy(childTraceEntries);
            parentChildMap.replaceValues(parentTraceEntry, childTraceEntries);
        }
        return getProtobufChildEntries(rootEntry, parentChildMap, startTick, captureTick);
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
        TraceEntryImpl entry = new TraceEntryImpl(threadContext, activeEntry, messageSupplier,
                queryData, queryExecutionCount, startTick, timer, null);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        activeEntry = entry;
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

    TraceEntryImpl addErrorEntry(long startTick, long endTick,
            @Nullable MessageSupplier messageSupplier, ErrorMessage errorMessage) {
        TraceEntryImpl entry = new TraceEntryImpl(threadContext, activeEntry, messageSupplier, null,
                1, startTick, null, null);
        entry.immediateEndAsErrorEntry(errorMessage, endTick);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        return entry;
    }

    TraceEntryImpl startAsyncEntry(long startTick, MessageSupplier messageSupplier,
            TimerImpl syncTimer, AsyncTimerImpl asyncTimer, @Nullable QueryData queryData,
            long queryExecutionCount) {
        TraceEntryImpl entry = new TraceEntryImpl(threadContext, activeEntry, messageSupplier,
                queryData, queryExecutionCount, startTick, syncTimer, asyncTimer);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        return entry;
    }

    @Nullable
    TraceEntryImpl getActiveEntry() {
        return activeEntry;
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
    private void popEntryBailout(TraceEntryImpl entry) {
        logger.error("found entry {} at top of stack when expecting entry {}", activeEntry, entry);
        if (entry == rootEntry) {
            activeEntry = null;
            return;
        }
        // don't pop the root trace entry
        while (activeEntry != null && activeEntry != rootEntry && activeEntry != entry) {
            activeEntry = activeEntry.getParentTraceEntry();
        }
    }

    private void logFoundNonRootEntryWithNullParent(TraceEntryImpl entry) {
        Transaction transaction = threadContext.getTransaction();
        MessageSupplier messageSupplier = entry.getMessageSupplier();
        ErrorMessage errorMessage = entry.getErrorMessage();
        String traceEntryMessage = "";
        if (messageSupplier != null) {
            ReadableMessage message = (ReadableMessage) messageSupplier.get();
            traceEntryMessage = message.getText();
        } else if (errorMessage != null) {
            traceEntryMessage = errorMessage.message();
        }
        logger.error("found non-root trace entry with null parent trace entry"
                + "\ntrace entry: {}\ntransaction: {} - {}", traceEntryMessage,
                transaction.getTransactionType(), transaction.getTransactionName());
    }

    private static List<Trace.Entry> getProtobufChildEntries(TraceEntryImpl entry,
            Multimap<TraceEntryImpl, TraceEntryImpl> parentChildMap, long transactionStartTick,
            long captureTick) {
        List<Trace.Entry> entries = Lists.newArrayList();
        addProtobufChildEntries(entry, parentChildMap, transactionStartTick, captureTick, 0,
                entries);
        return entries;
    }

    private static void addProtobufChildEntries(TraceEntryImpl entry,
            Multimap<TraceEntryImpl, TraceEntryImpl> parentChildMap, long transactionStartTick,
            long captureTick, int depth, List<Trace.Entry> entries) {
        if (!parentChildMap.containsKey(entry)) {
            return;
        }
        Collection<TraceEntryImpl> childEntries = parentChildMap.get(entry);
        for (TraceEntryImpl childEntry : childEntries) {
            entries.add(childEntry.toProto(depth, transactionStartTick, captureTick));
            addProtobufChildEntries(childEntry, parentChildMap, transactionStartTick, captureTick,
                    depth + 1, entries);
        }
    }
}
