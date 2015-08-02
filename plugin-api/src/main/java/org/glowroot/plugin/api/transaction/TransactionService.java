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
package org.glowroot.plugin.api.transaction;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.weaving.Pointcut;

/**
 * This is the primary service exposed to plugins. Plugins acquire a {@code TransactionService}
 * instance from {@link Agent#getTransactionService()}, and they can (and should) cache the
 * {@code TransactionService} instance for the life of the jvm to avoid looking it up every time it
 * is needed (which is often).
 */
public abstract class TransactionService {

    protected TransactionService() {}

    /**
     * Returns the {@code TimerName} instance for the specified {@code adviceClass}.
     * 
     * {@code adviceClass} must be a {@code Class} with a {@link Pointcut} annotation that has a
     * non-empty {@link Pointcut#timerName()}. This is how the {@code TimerName} is named.
     * 
     * The same {@code TimerName} is always returned for a given {@code adviceClass}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     */
    public abstract TimerName getTimerName(Class<?> adviceClass);

    /**
     * If there is no active transaction, a new transaction is started.
     * 
     * If there is already an active transaction, this method acts the same as
     * {@link #startTraceEntry(MessageSupplier, TimerName)} (the transaction name and type are not
     * modified on the existing transaction).
     */
    public abstract TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName);

    /**
     * Creates and starts a trace entry with the given {@code messageSupplier}. A timer for the
     * specified timer name is also started.
     * 
     * Since entries can be expensive in great quantities, there is a
     * {@code maxTraceEntriesPerTransaction} property on the configuration page to limit the number
     * of entries captured for any given trace.
     * 
     * Once a trace has accumulated {@code maxTraceEntriesPerTransaction} entries, this method
     * doesn't add new entries to the trace, but instead returns a dummy entry. A timer for the
     * specified timer name is still started, since timers are very cheap, even in great quantities.
     * The dummy entry adheres to the {@link TraceEntry} contract and returns the specified
     * {@link MessageSupplier} in response to {@link TraceEntry#getMessageSupplier()}. Calling
     * {@link TraceEntry#end()} on the dummy entry ends the timer. If
     * {@link TraceEntry#endWithError(ErrorMessage)} is called on the dummy entry, then the dummy
     * entry will be escalated to a real entry. If
     * {@link TraceEntry#endWithStackTrace(long, TimeUnit)} is called on the dummy entry and the
     * dummy entry duration exceeds the specified threshold, then the dummy entry will be escalated
     * to a real entry. If {@link TraceEntry#endWithError(ErrorMessage)} is called on the dummy
     * entry, then the dummy entry will be escalated to a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     * 
     * If there is no current transaction, this method does nothing, and returns a no-op instance of
     * {@link TraceEntry}.
     */
    public abstract TraceEntry startTraceEntry(MessageSupplier messageSupplier,
            TimerName timerName);

    /**
     * {@link QueryEntry} is a specialized type of {@link TraceEntry} that is aggregated by its
     * query text.
     */
    public abstract QueryEntry startQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName);

    /**
     * {@link QueryEntry} is a specialized type of {@link TraceEntry} that is aggregated by its
     * query text.
     */
    public abstract QueryEntry startQueryEntry(String queryType, String queryText,
            long queryExecutionCount, MessageSupplier messageSupplier, TimerName timerName);

    /**
     * Starts a timer for the specified timer name. If a timer is already running for the specified
     * timer name, it will keep an internal counter of the number of starts, and it will only end
     * the timer after the corresponding number of ends.
     * 
     * If there is no current transaction, this method does nothing, and returns a no-op instance of
     * {@link Timer}.
     */
    public abstract Timer startTimer(TimerName timerName);

    /**
     * Adds a trace entry with duration zero. It does not set the error attribute on the trace,
     * which must be done with {@link TraceEntry#endWithError(ErrorMessage)} on the root entry.
     * 
     * If the error message has no throwable, a stack trace is captured and attached to the trace
     * entry.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void addTraceEntry(ErrorMessage errorMessage);

    /**
     * Set the transaction type that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionType(@Nullable String transactionType);

    /**
     * Set the transaction name that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionName(@Nullable String transactionName);

    /**
     * Marks the transaction as an error with the given message. Normally transactions are only
     * marked as an error if {@link TraceEntry#endWithError(ErrorMessage)} is called on the root
     * entry. This method can be used to mark the entire transaction as an error from a nested
     * entry.
     * 
     * This should be used sparingly. Normally, entries should only mark themselves (using
     * {@link TraceEntry#endWithError(ErrorMessage)}), and let the root entry determine if the
     * transaction as a whole should be marked as an error.
     * 
     * E.g., this method is called from the logger plugin, to mark the entire transaction as an
     * error if an error is logged through one of the supported logger APIs.
     * 
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionError(ErrorMessage errorMessage);

    /**
     * Sets the user attribute on the transaction. This attribute is shared across all plugins, and
     * is generally set by the plugin that initiated the trace, but can be set by other plugins if
     * needed.
     * 
     * The user is used in a few ways:
     * <ul>
     * <li>The user is displayed when viewing a trace on the trace explorer page
     * <li>Traces can be filtered by their user on the trace explorer page
     * <li>Glowroot can be configured (using the configuration page) to capture traces for a
     * specific user using a lower threshold than normal (e.g. threshold=0 to capture all requests
     * for a specific user)
     * <li>Glowroot can be configured (using the configuration page) to perform profiling on all
     * transactions for a specific user
     * </ul>
     * 
     * If profiling is enabled for a specific user, this is activated (if the {@code user} matches)
     * at the time that this method is called, so it is best to call this method early in the
     * transaction.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionUser(@Nullable String user);

    /**
     * Adds an attribute on the current transaction with the specified {@code name} and
     * {@code value}. A transaction's attributes are displayed when viewing a trace on the trace
     * explorer page.
     * 
     * Subsequent calls to this method with the same {@code name} on the same transaction will add
     * an additional attribute if there is not already an attribute with the same {@code name} and
     * {@code value}.
     * 
     * If there is no current transaction, this method does nothing.
     * 
     * {@code null} values are normalized to the empty string.
     */
    public abstract void addTransactionCustomAttribute(String name, @Nullable String value);

    /**
     * Overrides the default slow trace threshold (Configuration &gt; General &gt; Slow trace
     * threshold) for the current transaction. This can be used to store particular traces at a
     * lower or higher threshold than the general threshold.
     * 
     * If this is called multiple times for a given transaction, the minimum {@code threshold} will
     * be used.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setSlowTraceThreshold(long threshold, TimeUnit unit);

    /**
     * Returns whether a transaction is already being captured.
     * 
     * This method has very limited use. It should only be used by top-level pointcuts that define a
     * transaction, and that do not want to create a entry if they are already inside of an existing
     * transaction.
     */
    public abstract boolean isInTransaction();
}
