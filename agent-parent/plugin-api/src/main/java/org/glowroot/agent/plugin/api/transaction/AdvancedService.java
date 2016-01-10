/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.api.transaction;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public interface AdvancedService {

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
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    void setTransactionError(@Nullable Throwable t);

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
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
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
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    void setTransactionError(@Nullable String message, @Nullable Throwable t);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with
     * {@link TransactionService#setTransactionError} or with {@code endWithError} on the root
     * entry.
     * 
     * The error message text is captured from {@code Throwable#getMessage()}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction, this method does nothing.
     */
    void addErrorEntry(Throwable t);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with
     * {@link TransactionService#setTransactionError} or with {@code endWithError} on the root
     * entry.
     * 
     * Since there is no throwable passed to this variant, a stack trace is captured and displayed
     * in the UI as a location stack trace (as opposed to an exception stack trace), similar to
     * {@link TraceEntry#endWithStackTrace(long, TimeUnit)}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction, this method does nothing.
     */
    void addErrorEntry(@Nullable String message);

    /**
     * Adds a trace entry with the specified error message and total time of zero. It does not set
     * the error attribute on the transaction, which must be done with
     * {@link TransactionService#setTransactionError} or with {@code endWithError} on the root
     * entry.
     * 
     * If {@code message} is null, then the error message is captured from
     * {@code Throwable#getMessage()}.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction, this method does nothing.
     */
    void addErrorEntry(@Nullable String message, Throwable t);

    /**
     * Returns whether a transaction is already being captured.
     * 
     * This method has very limited use. It should only be used by top-level pointcuts that define a
     * transaction, and that do not want to create a entry if they are already inside of an existing
     * transaction.
     */
    boolean isInTransaction();

    QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName);
}
