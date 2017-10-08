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
package org.glowroot.agent.impl;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService;

import static org.glowroot.agent.util.Checkers.castInitialized;

// TODO this has ability to retain a transaction beyond its completion
// but don't use WeakReference because it needs to be able to retain transaction in async case
// (at least until transaction completion)
// so ideally clear references here at transaction completion
class AuxThreadContextImpl implements AuxThreadContext {

    private static final Logger logger = LoggerFactory.getLogger(AuxThreadContextImpl.class);

    static final ThreadLocal</*@Nullable*/ Boolean> inAuxDebugLogging =
            new ThreadLocal</*@Nullable*/ Boolean>();

    private final Transaction transaction;
    // null when parent is a limit exceeded auxiliary thread context, to prevent retaining parent
    private final @Nullable TraceEntryImpl parentTraceEntry;
    // null when parent is a limit exceeded auxiliary thread context, to prevent retaining parent
    private final @Nullable TraceEntryImpl parentThreadContextPriorEntry;
    private final @Nullable ServletRequestInfo servletRequestInfo;
    private final @Nullable ImmutableList<StackTraceElement> locationStackTrace;
    private final TransactionRegistry transactionRegistry;
    private final TransactionServiceImpl transactionService;

    AuxThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry,
            @Nullable ServletRequestInfo servletRequestInfo,
            @Nullable ImmutableList<StackTraceElement> locationStackTrace,
            TransactionRegistry transactionRegistry, TransactionServiceImpl transactionService) {
        this.transaction = transaction;
        this.parentTraceEntry = parentTraceEntry;
        this.parentThreadContextPriorEntry = parentThreadContextPriorEntry;
        this.servletRequestInfo = servletRequestInfo;
        this.locationStackTrace = locationStackTrace;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
        if (logger.isDebugEnabled()
                && !Thread.currentThread().getName().startsWith("Glowroot-GRPC-")
                && inAuxDebugLogging.get() == null) {
            inAuxDebugLogging.set(Boolean.TRUE);
            try {
                logger.debug(
                        "new AUX thread context: {}, parent thread context: {}, thread name: {}",
                        castInitialized(this).hashCode(), getThreadContextDisplay(parentTraceEntry),
                        Thread.currentThread().getName(), new Exception());
            } finally {
                inAuxDebugLogging.remove();
            }
        }
    }

    @Override
    public TraceEntry start() {
        return start(false);
    }

    @Override
    public TraceEntry startAndMarkAsyncTransactionComplete() {
        return start(true);
    }

    private TraceEntry start(boolean completeAsyncTransaction) {
        ThreadContextThreadLocal.Holder threadContextHolder =
                transactionRegistry.getCurrentThreadContextHolder();
        ThreadContextImpl context = threadContextHolder.get();
        if (context != null) {
            if (completeAsyncTransaction) {
                context.setTransactionAsyncComplete();
            }
            return NopTransactionService.TRACE_ENTRY;
        }
        context = transactionService.startAuxThreadContextInternal(transaction, parentTraceEntry,
                parentThreadContextPriorEntry, servletRequestInfo, threadContextHolder);
        if (context == null) {
            // transaction is already complete or auxiliary thread context limit exceeded
            return NopTransactionService.TRACE_ENTRY;
        }
        if (logger.isDebugEnabled()
                && !Thread.currentThread().getName().startsWith("Glowroot-GRPC-")
                && inAuxDebugLogging.get() == null) {
            inAuxDebugLogging.set(Boolean.TRUE);
            try {
                logger.debug("start AUX thread context: {}, thread context: {},"
                        + " parent thread context: {}, thread name: {}", hashCode(),
                        context.hashCode(), getThreadContextDisplay(parentTraceEntry),
                        Thread.currentThread().getName(), new Exception());
            } finally {
                inAuxDebugLogging.remove();
            }
        }
        if (completeAsyncTransaction) {
            context.setTransactionAsyncComplete();
        }
        TraceEntryImpl rootEntry = context.getRootEntry();
        if (locationStackTrace != null) {
            rootEntry.setLocationStackTrace(locationStackTrace);
        }
        return rootEntry;
    }

    private static Object getThreadContextDisplay(@Nullable TraceEntryImpl parentTraceEntry) {
        if (parentTraceEntry == null) {
            return "null (aux thread context limit exceeded)";
        } else {
            return parentTraceEntry.getThreadContext().hashCode();
        }
    }
}
