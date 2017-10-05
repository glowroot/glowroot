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
import org.glowroot.agent.plugin.api.internal.NopTransactionService;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAuxThreadContext;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.common.util.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;

public class OptionalThreadContextImpl implements ThreadContextPlus {

    private static final Logger logger = LoggerFactory.getLogger(OptionalThreadContextImpl.class);

    private @MonotonicNonNull ThreadContextImpl threadContext;

    private final TransactionServiceImpl transactionService;
    private final ThreadContextThreadLocal.Holder threadContextHolder;

    @UsedByGeneratedBytecode
    public static OptionalThreadContextImpl create(TransactionServiceImpl transactionService,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        return new OptionalThreadContextImpl(transactionService, threadContextHolder);
    }

    private OptionalThreadContextImpl(TransactionServiceImpl transactionService,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        this.transactionService = transactionService;
        this.threadContextHolder = threadContextHolder;
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (threadContext == null) {
            TraceEntry traceEntry = transactionService.startTransaction(transactionType,
                    transactionName, messageSupplier, timerName, threadContextHolder);
            threadContext = checkNotNull(threadContextHolder.get());
            return traceEntry;
        } else {
            return threadContext.startTransaction(transactionType, transactionName, messageSupplier,
                    timerName);
        }
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.TRACE_ENTRY;
        }
        return threadContext.startTraceEntry(messageSupplier, timerName);
    }

    @Override
    public AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        return threadContext.startAsyncTraceEntry(messageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.QUERY_ENTRY;
        }
        return threadContext.startQueryEntry(queryType, queryText, queryMessageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.QUERY_ENTRY;
        }
        return threadContext.startQueryEntry(queryType, queryText, queryExecutionCount,
                queryMessageSupplier, timerName);
    }

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        return threadContext.startAsyncQueryEntry(queryType, queryText, queryMessageSupplier,
                timerName);
    }

    @Override
    public TraceEntry startServiceCallEntry(String serviceCallType, String serviceCallText,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.TRACE_ENTRY;
        }
        return threadContext.startServiceCallEntry(serviceCallType, serviceCallText,
                messageSupplier, timerName);
    }

    @Override
    public AsyncTraceEntry startAsyncServiceCallEntry(String serviceCallType,
            String serviceCallText,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (threadContext == null) {
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        return threadContext.startAsyncServiceCallEntry(serviceCallType, serviceCallText,
                messageSupplier, timerName);
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
    public void setTransactionAsync() {
        if (threadContext != null) {
            threadContext.setTransactionAsync();
        }
    }

    @Override
    public void setTransactionAsyncComplete() {
        if (threadContext != null) {
            threadContext.setTransactionAsyncComplete();
        }
    }

    @Override
    public void setTransactionOuter() {
        if (threadContext != null) {
            threadContext.setTransactionOuter();
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
    public @Nullable ServletRequestInfo getServletRequestInfo() {
        if (threadContext != null) {
            return threadContext.getServletRequestInfo();
        }
        return null;
    }

    @Override
    public void setServletRequestInfo(@Nullable ServletRequestInfo servletRequestInfo) {
        if (threadContext != null) {
            threadContext.setServletRequestInfo(servletRequestInfo);
        }
    }

    @Override
    @Deprecated
    public @Nullable MessageSupplier getServletMessageSupplier() {
        ServletRequestInfo servletRequestInfo = getServletRequestInfo();
        if (servletRequestInfo instanceof MessageSupplier) {
            return (MessageSupplier) servletRequestInfo;
        } else {
            return null;
        }
    }

    @Override
    @Deprecated
    public void setServletMessageSupplier(@Nullable MessageSupplier messageSupplier) {
        if (messageSupplier instanceof ServletRequestInfo) {
            setServletRequestInfo((ServletRequestInfo) messageSupplier);
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
