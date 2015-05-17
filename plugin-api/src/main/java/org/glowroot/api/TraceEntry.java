/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.api;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.glowroot.api.weaving.OnReturn;

/**
 * See {@link PluginServices#startTraceEntry(MessageSupplier, TimerName)} for how to create and use
 * {@code TraceEntry} instances.
 */
public interface TraceEntry {

    /**
     * End the entry.
     */
    void end();

    /**
     * End the entry and capture a stack trace if its duration exceeds the specified
     * {@code threshold}.
     * 
     * In case the trace has accumulated {@code maxTraceEntriesPerTransaction} entries and this is a
     * dummy entry and its duration exceeds the specified threshold, then this dummy entry is
     * escalated into a real entry. A hard cap ({@code maxTraceEntriesPerTransaction * 2}) on the
     * total number of (real) entries is applied when escalating dummy entries to real entries.
     */
    void endWithStackTrace(long threshold, TimeUnit unit);

    /**
     * End the entry and add the specified {@code errorMessage} to the entry.
     * 
     * If this is the root entry, then the error flag on the trace is set. Traces can be filtered by
     * their error flag on the trace explorer page.
     * 
     * In case the trace has accumulated {@code maxTraceEntriesPerTransaction} entries and this is a
     * dummy entry, then this dummy entry is escalated into a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     */
    void endWithError(ErrorMessage errorMessage);

    /**
     * Example of query and subsequent iterating over results which goes back to database and pulls
     * more results.
     */
    Timer extend();

    /**
     * Returns the {@code MessageSupplier} that was supplied when the {@code TraceEntry} was
     * created.
     * 
     * This can be useful (for example) to retrieve the {@code MessageSupplier} in @
     * {@link OnReturn} so that the return value can be added to the message produced by the
     * {@code MessageSupplier}.
     * 
     * This returns the {@code MessageSupplier} even if the trace has accumulated
     * {@code maxTraceEntriesPerTransaction} entries and this is a dummy entry.
     * 
     * Under some error conditions this can return {@code null}.
     */
    @Nullable
    MessageSupplier getMessageSupplier();
}
