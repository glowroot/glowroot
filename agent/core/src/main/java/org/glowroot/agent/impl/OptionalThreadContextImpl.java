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
package org.glowroot.agent.impl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.ThreadContextPlus;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncTraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAuxThreadContext;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.common.util.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;

public class OptionalThreadContextImpl implements ThreadContextPlus {

    private static final Logger logger = LoggerFactory.getLogger(OptionalThreadContextImpl.class);

    private @MonotonicNonNull ThreadContextImpl threadContext;

    private final TransactionServiceImpl transactionService;
    private final Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder;

    @UsedByGeneratedBytecode
    public static OptionalThreadContextImpl create(TransactionServiceImpl transactionService,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        return new OptionalThreadContextImpl(transactionService, threadContextHolder);
    }

    private OptionalThreadContextImpl(TransactionServiceImpl transactionService,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        this.transactionService = transactionService;
        this.threadContextHolder = threadContextHolder;
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (threadContext == null) {
            TraceEntry traceEntry = transactionService.startTransaction(transactionType,
                    transactionName, messageSupplier, timerName, threadContextHolder);
            ThreadContextImpl threadContext = threadContextHolder.get();
            checkNotNull(threadContext);
            this.threadContext = threadContext;
            return traceEntry;
        } else {
            return threadContext.startTransaction(transactionType, transactionName, messageSupplier,
                    timerName);
        }
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTraceEntry.INSTANCE;
        }
        return threadContext.startTraceEntry(messageSupplier, timerName);
    }

    @Override
    public AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName timerName) {
        if (threadContext == null) {
            return NopAsyncQueryEntry.INSTANCE;
        }
        return threadContext.startAsyncTraceEntry(messageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopQueryEntry.INSTANCE;
        }
        return threadContext.startQueryEntry(queryType, queryText, queryMessageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopQueryEntry.INSTANCE;
        }
        return threadContext.startQueryEntry(queryType, queryText, queryExecutionCount,
                queryMessageSupplier, timerName);
    }

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopAsyncQueryEntry.INSTANCE;
        }
        return threadContext.startAsyncQueryEntry(queryType, queryText, queryMessageSupplier,
                timerName);
    }

    @Override
    public TraceEntry startServiceCallEntry(String type, String text,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTraceEntry.INSTANCE;
        }
        return threadContext.startServiceCallEntry(type, text,
                messageSupplier,
                timerName);
    }

    @Override
    public AsyncTraceEntry startAsyncServiceCallEntry(String type, String text,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopAsyncTraceEntry.INSTANCE;
        }
        return threadContext.startAsyncServiceCallEntry(type, text, messageSupplier, timerName);
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        if (threadContext == null) {
            return NopTimer.INSTANCE;
        }
        return threadContext.startTimer(timerName);
    }

    @Override
    public AuxThreadContext createAuxThreadContext() {
        if (threadContext == null) {
            return NopAuxThreadContext.INSTANCE;
        }
        return threadContext.createAuxThreadContext();
    }

    @Override
    public void setAsyncTransaction() {
        if (threadContext != null) {
            threadContext.setAsyncTransaction();
        }
    }

    @Override
    public void completeAsyncTransaction() {
        if (threadContext != null) {
            threadContext.completeAsyncTransaction();
        }
    }

    @Override
    public void setOuterTransaction() {
        if (threadContext != null) {
            threadContext.setOuterTransaction();
        }
    }

    @Override
    public void setTransactionType(@Nullable String transactionType, int priority) {
        if (threadContext != null) {
            threadContext.setTransactionType(transactionType, priority);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName, int priority) {
        if (threadContext != null) {
            threadContext.setTransactionName(transactionName, priority);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user, int priority) {
        if (threadContext != null) {
            threadContext.setTransactionUser(user, priority);
        }
    }

    @Override
    public void addTransactionAttribute(String name, @Nullable String value) {
        if (threadContext != null) {
            threadContext.addTransactionAttribute(name, value);
        }
    }

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit, int priority) {
        if (threadContext != null) {
            threadContext.setTransactionSlowThreshold(threshold, unit, priority);
        }
    }

    @Override
    public void setTransactionError(Throwable t) {
        if (threadContext != null) {
            threadContext.setTransactionError(t);
        }
    }

    @Override
    public void setTransactionError(@Nullable String message) {
        if (threadContext != null) {
            threadContext.setTransactionError(message);
        }
    }

    @Override
    public void setTransactionError(@Nullable String message, @Nullable Throwable t) {
        if (threadContext != null) {
            threadContext.setTransactionError(message, t);
        }
    }

    @Override
    public void addErrorEntry(Throwable t) {
        if (threadContext != null) {
            threadContext.addErrorEntry(t);
        }
    }

    @Override
    public void addErrorEntry(@Nullable String message) {
        if (threadContext != null) {
            threadContext.addErrorEntry(message);
        }
    }

    @Override
    public void addErrorEntry(@Nullable String message, Throwable t) {
        if (threadContext != null) {
            threadContext.addErrorEntry(message, t);
        }
    }

    @Override
    public @Nullable MessageSupplier getServletMessageSupplier() {
        if (threadContext != null) {
            return threadContext.getServletMessageSupplier();
        }
        return null;
    }

    @Override
    public void setServletMessageSupplier(@Nullable MessageSupplier messageSupplier) {
        if (threadContext != null) {
            threadContext.setServletMessageSupplier(messageSupplier);
        }
    }

    @Override
    public int getCurrentNestingGroupId() {
        if (threadContext == null) {
            return 0;
        }
        return threadContext.getCurrentNestingGroupId();
    }

    @Override
    public void setCurrentNestingGroupId(int nestingGroupId) {
        if (threadContext != null) {
            threadContext.setCurrentNestingGroupId(nestingGroupId);
        }
    }

    @Override
    public int getCurrentSuppressionKeyId() {
        if (threadContext == null) {
            return 0;
        }
        return threadContext.getCurrentSuppressionKeyId();
    }

    @Override
    public void setCurrentSuppressionKeyId(int suppressionKeyId) {
        if (threadContext != null) {
            threadContext.setCurrentSuppressionKeyId(suppressionKeyId);
        }
    }
}
