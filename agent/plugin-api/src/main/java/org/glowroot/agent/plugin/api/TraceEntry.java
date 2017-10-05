/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.plugin.api;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.weaving.OnReturn;

/**
 * See {@link ThreadContext#startTraceEntry(MessageSupplier, TimerName)} for how to create and use
 * {@code TraceEntry} instances.
 */
public interface TraceEntry {

    /**
     * End the entry.
     */
    void end();

    /**
     * End the entry and capture a stack trace if its total time exceeds the specified
     * {@code threshold}.
     * 
     * In case the trace has accumulated {@code maxTraceEntriesPerTransaction} entries and this is a
     * dummy entry and its total time exceeds the specified threshold, then this dummy entry is
     * escalated into a real entry. A hard cap ({@code maxTraceEntriesPerTransaction * 2}) on the
     * total number of (real) entries is applied when escalating dummy entries to real entries.
     * 
     * This is a no-op for async trace entries (those created by
     * {@link ThreadContext#startAsyncTraceEntry(MessageSupplier, TimerName)} and
     * {@link ThreadContext#startAsyncQueryEntry(String, String, QueryMessageSupplier, TimerName)}).
     * This is because async trace entries are used when their end is performed by a different
     * thread, and so a stack trace at that time does not point to the code which executed triggered
     * the trace entry creation.
     */
    void endWithLocationStackTrace(long threshold, TimeUnit unit);

    /**
     * End the entry and mark the trace entry as an error with the specified throwable.
     * 
     * The error message text is captured from {@code Throwable#getMessage()}.
     * 
     * If this is the root entry, then the error flag on the transaction is set.
     * 
     * In case the transaction has accumulated {@code maxTraceEntriesPerTransaction} entries and
     * this is a dummy entry, then this dummy entry is escalated into a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     */
    void endWithError(Throwable t);

    /**
     * End the entry and mark the trace entry as an error with the specified throwable.
     * 
     * A stack trace is captured and displayed in the UI as a location stack trace (as opposed to an
     * exception stack trace), similar to {@link #endWithLocationStackTrace(long, TimeUnit)}. Unless
     * this is the root trace entry in which case no location stack trace is captured / displayed
     * (since location stack trace is typically not mysterious for root trace entries).
     * 
     * If this is the root entry, then the error flag on the transaction is set.
     * 
     * In case the transaction has accumulated {@code maxTraceEntriesPerTransaction} entries and
     * this is a dummy entry, then this dummy entry is escalated into a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     */
    void endWithError(@Nullable String message);

    /**
     * End the entry and add the specified {@code errorMessage} to the entry.
     * 
     * If {@code message} is empty or null, then the error message text is captured from
     * {@code Throwable#getMessage()}.
     * 
     * If this is the root entry, then the error flag on the transaction is set.
     * 
     * In case the transaction has accumulated {@code maxTraceEntriesPerTransaction} entries and
     * this is a dummy entry, then this dummy entry is escalated into a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     */
    void endWithError(@Nullable String message, Throwable t);

    /**
     * This method is the same as {@link #endWithError(Throwable)}, except that it won't escalate a
     * dummy entry into a real entry.
     */
    void endWithInfo(Throwable t);

    /**
     * Example of query and subsequent iterating over results which goes back to database and pulls
     * more results.
     * 
     * Important note for async trace entries (those created by
     * {@link ThreadContext#startAsyncTraceEntry(MessageSupplier, TimerName)} and
     * {@link ThreadContext#startAsyncQueryEntry(String, String, QueryMessageSupplier, TimerName)}):
     * this method should not be used by a thread other than the one that created the async trace
     * entry.
     */
    Timer extend();

    /**
     * Returns the message supplier that was supplied when the {@code TraceEntry} was created.
     * 
     * This can be useful (for example) to retrieve the message supplier in @{@link OnReturn} so
     * that the return value can be added to the message produced by the {@code MessageSupplier}.
     * 
     * This returns the message supplier even if the trace has accumulated
     * {@code maxTraceEntriesPerTransaction} entries and this is a dummy entry.
     * 
     * Under some error conditions this can return {@code null}.
     */
    @Nullable
    Object getMessageSupplier();
}
