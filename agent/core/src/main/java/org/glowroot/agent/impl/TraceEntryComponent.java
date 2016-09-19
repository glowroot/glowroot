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
package org.glowroot.agent.impl;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.AsyncTimerImpl;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.plugin.api.MessageSupplier;

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

    private TraceEntryImpl activeEntry;

    private TraceEntryImpl tailEntry;

    TraceEntryComponent(ThreadContextImpl threadContext, MessageSupplier messageSupplier,
            TimerImpl timer, long startTick) {
        this.threadContext = threadContext;
        this.startTick = startTick;
        rootEntry = new TraceEntryImpl(threadContext, null, messageSupplier, null, 0, startTick,
                timer, null);
        activeEntry = rootEntry;
        tailEntry = rootEntry;
    }

    TraceEntryImpl getRootEntry() {
        return rootEntry;
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

    TraceEntryImpl pushEntry(long startTick, Object messageSupplier, TimerImpl syncTimer,
            @Nullable AsyncTimerImpl asyncTimer, @Nullable QueryData queryData,
            long queryExecutionCount) {
        TraceEntryImpl entry = new TraceEntryImpl(threadContext, activeEntry, messageSupplier,
                queryData, queryExecutionCount, startTick, syncTimer, asyncTimer);
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
        if (entry == rootEntry) {
            this.endTick = endTick;
            this.completed = true;
        }
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. an entry never being marked as complete)
    void popNonRootEntry(TraceEntryImpl entry) {
        popEntrySafe(entry);
    }

    TraceEntryImpl addErrorEntry(long startTick, long endTick, @Nullable Object messageSupplier,
            @Nullable QueryData queryData, ErrorMessage errorMessage) {
        TraceEntryImpl entry = new TraceEntryImpl(threadContext, activeEntry, messageSupplier,
                queryData, 1, startTick, null, null);
        entry.immediateEndAsErrorEntry(errorMessage, endTick);
        tailEntry.setNextTraceEntry(entry);
        tailEntry = entry;
        return entry;
    }

    TraceEntryImpl getActiveEntry() {
        return activeEntry;
    }

    TraceEntryImpl getTailEntry() {
        return tailEntry;
    }

    boolean isEmpty() {
        return rootEntry == tailEntry;
    }

    private void popEntrySafe(TraceEntryImpl entry) {
        if (activeEntry != entry) {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            popEntryBailout(entry);
            return;
        }
        TraceEntryImpl parentTraceEntry = activeEntry.getParentTraceEntry();
        if (parentTraceEntry != null) {
            // don't pop the root entry in case an async trace that exceeded the trace entry limit
            // is still active, and ends with an error, but has not exceeded the extra error entry
            // limit, so then adds a trace entry error, and so still needs an "active trace"
            activeEntry = parentTraceEntry;
        }
    }

    // split typically unused path into separate method to not affect inlining budget
    private void popEntryBailout(TraceEntryImpl entry) {
        logger.error("found entry {} at top of stack when expecting entry {}", activeEntry, entry,
                new Exception("location stack trace"));
        TraceEntryImpl parentTraceEntry = activeEntry.getParentTraceEntry();
        while (activeEntry != entry && parentTraceEntry != null) {
            activeEntry = parentTraceEntry;
            parentTraceEntry = parentTraceEntry.getParentTraceEntry();
        }
    }
}
