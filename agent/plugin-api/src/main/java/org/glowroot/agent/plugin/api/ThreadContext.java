/*
 * Copyright 2016-2017 the original author or authors.
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

public interface ThreadContext {

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
     * {@link TraceEntry#end()} on the dummy entry ends the timer. If {@code endWithError} is called
     * on the dummy entry, then the dummy entry will be escalated to a real entry. If
     * {@link TraceEntry#endWithLocationStackTrace(long, TimeUnit)} is called on the dummy entry and
     * the dummy entry total time exceeds the specified threshold, then the dummy entry will be
     * escalated to a real entry. If {@code endWithError} is called on the dummy entry, then the
     * dummy entry will be escalated to a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     * 
     * If there is no current transaction then this method does nothing, and returns a no-op
     * instance of {@link TraceEntry}.
     */
    TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName);

    AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier, TimerName timerName);

    /**
     * {@link QueryEntry} is a specialized type of {@link TraceEntry} that is aggregated by its
     * query text.
     */
    QueryEntry startQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName);

    QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName);

    AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName);

    TraceEntry startServiceCallEntry(String type, String text, MessageSupplier messageSupplier,
            TimerName timerName);

    AsyncTraceEntry startAsyncServiceCallEntry(String type, String text,
            MessageSupplier messageSupplier, TimerName timerName);

    /**
     * Starts a timer for the specified timer name. If a timer is already running for the specified
     * timer name, it will keep an internal counter of the number of starts, and it will only end
     * the timer after the corresponding number of ends.
     * 
     * If there is no current transaction then this method does nothing, and returns a no-op
     * instance of {@link Timer}.
     */
    Timer startTimer(TimerName timerName);

    /**
     * TODO
     */
    AuxThreadContext createAuxThreadContext();

    /**
     * TODO
     */
    void setTransactionAsync();

    /**
     * TODO
     */
    void setTransactionAsyncComplete();

    /**
     * This should be used in very limited circumstances. E.g. a really long "outer" transaction
     * that processes thousands of objects, where it is useful to track the processing details per
     * object as separate transactions, but also useful to track the overarching long "outer"
     * transaction.
     * 
     * Once a transaction is marked as an outer transaction, then
     * {@link OptionalThreadContext#startTransaction(String, String, MessageSupplier, TimerName)}
     * will start a new "inner" transaction.
     * 
     * To start a new "inner" transaction, the active "outer" transaction is unbound from the
     * thread, and the new "inner" transaction is started and bound to the thread. When the "inner"
     * transaction ends, the previously active "outer" transaction is bound back to the thread.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionOuter();

    /**
     * Set the transaction type that is used for aggregation.
     * 
     * Calling this method with a non-null non-empty value overrides the transaction type set in
     * {@link OptionalThreadContext#startTransaction(String, String, MessageSupplier, TimerName)}.
     * 
     * If this method is called multiple times within a single transaction, the highest priority
     * non-null non-empty value wins, and priority ties go to the first caller.
     * 
     * See {@link Priority} for common priority values.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionType(@Nullable String transactionType, int priority);

    /**
     * Set the transaction name that is used for aggregation.
     *
     * Calling this method with a non-null non-empty value overrides the transaction name set in
     * {@link OptionalThreadContext#startTransaction(String, String, MessageSupplier, TimerName)}.
     * 
     * If this method is called multiple times within a single transaction, the highest priority
     * non-null non-empty value wins, and priority ties go to the first caller.
     * 
     * See {@link Priority} for common priority values.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionName(@Nullable String transactionName, int priority);

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
     * If this method is called multiple times within a single transaction, the highest priority
     * non-null non-empty value wins, and priority ties go to the first caller.
     * 
     * See {@link Priority} for common priority values.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionUser(@Nullable String user, int priority);

    /**
     * Adds an attribute on the current transaction with the specified {@code name} and
     * {@code value}. A transaction's attributes are displayed when viewing a trace on the trace
     * explorer page.
     * 
     * Subsequent calls to this method with the same {@code name} on the same transaction will add
     * an additional attribute if there is not already an attribute with the same {@code name} and
     * {@code value}.
     * 
     * If there is no current transaction then this method does nothing.
     * 
     * {@code null} values are normalized to the empty string.
     */
    void addTransactionAttribute(String name, @Nullable String value);

    /**
     * Overrides the default slow trace threshold (Configuration &gt; General &gt; Slow trace
     * threshold) for the current transaction. This can be used to store particular traces at a
     * lower or higher threshold than the general threshold.
     * 
     * If this method is called multiple times within a single transaction, the highest priority
     * non-null non-empty value wins, and priority ties go to the first caller.
     * 
     * See {@link Priority} for common priority values.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionSlowThreshold(long threshold, TimeUnit unit, int priority);

    /**
     * Marks the transaction as an error with the given message. Normally transactions are only
     * marked as an error if {@code endWithError} is called on the root entry. This method can be
     * used to mark the entire transaction as an error from a nested entry.
     * 
     * The error message text is captured from {@code Throwable#getMessage()}.
     * 
     * This should be used sparingly. Normally, entries should only mark themselves (using
     * {@code endWithError}), and let the root entry determine if the transaction as a whole should
     * be marked as an error.
     * 
     * E.g., this method is called from the logger plugin, to mark the entire transaction as an
     * error if an error is logged through one of the supported logger APIs.
     * 
     * If this method is called multiple times within a single transaction, only the first call has
     * any effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionError(Throwable t);

    /**
     * Marks the transaction as an error with the given message. Normally transactions are only
     * marked as an error if {@code endWithError} is called on the root entry. This method can be
     * used to mark the entire transaction as an error from a nested entry.
     * 
     * This should be used sparingly. Normally, entries should only mark themselves (using
     * {@code endWithError}), and let the root entry determine if the transaction as a whole should
     * be marked as an error.
     * 
     * E.g., this method is called from the logger plugin, to mark the entire transaction as an
     * error if an error is logged through one of the supported logger APIs.
     * 
     * If this method is called multiple times within a single transaction, only the first call has
     * any effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionError(@Nullable String message);

    /**
     * Marks the transaction as an error with the given message. Normally transactions are only
     * marked as an error if {@code endWithError} is called on the root entry. This method can be
     * used to mark the entire transaction as an error from a nested entry.
     * 
     * If {@code message} is empty or null, then the error message text is captured from
     * {@code Throwable#getMessage()}.
     * 
     * This should be used sparingly. Normally, entries should only mark themselves (using
     * {@code endWithError}), and let the root entry determine if the transaction as a whole should
     * be marked as an error.
     * 
     * E.g., this method is called from the logger plugin, to mark the entire transaction as an
     * error if an error is logged through one of the supported logger APIs.
     * 
     * If this method is called multiple times within a single transaction, only the first call has
     * any effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void setTransactionError(@Nullable String message, @Nullable Throwable t);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with {@link #setTransactionError}
     * or with {@code endWithError} on the root entry.
     * 
     * The error message text is captured from {@code Throwable#getMessage()}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void addErrorEntry(Throwable t);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with {@link #setTransactionError}
     * or with {@code endWithError} on the root entry.
     * 
     * Since there is no throwable passed to this variant, a stack trace is captured and displayed
     * in the UI as a location stack trace (as opposed to an exception stack trace), similar to
     * {@link TraceEntry#endWithLocationStackTrace(long, TimeUnit)}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void addErrorEntry(@Nullable String message);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with {@link #setTransactionError}
     * or with {@code endWithError} on the root entry.
     * 
     * If {@code message} is null, then the error message is captured from
     * {@code Throwable#getMessage()}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction then this method does nothing.
     */
    void addErrorEntry(@Nullable String message, Throwable t);

    @Nullable
    ServletRequestInfo getServletRequestInfo();

    /**
     * DO NOT USE.
     * 
     * This method should only ever be used by the servlet plugin.
     */
    void setServletRequestInfo(@Nullable ServletRequestInfo servletRequestInfo);

    /**
     * @deprecated Replaced by {@link #getServletRequestInfo()}.
     */
    @Deprecated
    @Nullable
    MessageSupplier getServletMessageSupplier();

    /**
     * @deprecated Replaced by {@link #setServletRequestInfo(ServletRequestInfo)}.
     */
    @Deprecated
    void setServletMessageSupplier(@Nullable MessageSupplier messageSupplier);

    interface ServletRequestInfo {
        String getMethod();
        String getContextPath();
        String getServletPath();
        // getPathInfo() returns null when the servlet is mapped to "/" (not "/*") and therefore it
        // is replacing the default servlet and in this case getServletPath() returns the full path
        @Nullable
        String getPathInfo();
        String getUri();
    }

    public final class Priority {

        public static final int CORE_PLUGIN = -100;
        public static final int USER_PLUGIN = 100;
        public static final int USER_API = 1000;
        public static final int USER_CONFIG = 10000;
        // this is used for very special circumstances, currently only
        // when setting transaction name from HTTP header "Glowroot-Transaction-Type"
        // and for setting slow threshold (to zero) for Startup transactions
        // and for setting slow threshold for user-specific profiling
        public static final int CORE_MAX = 1000000;

        private Priority() {}
    }
}
