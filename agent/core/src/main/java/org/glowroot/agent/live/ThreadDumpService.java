/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.live;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.ThreadContextImpl;
import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

class ThreadDumpService {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpService.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;

    ThreadDumpService(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
    }

    ThreadDump getThreadDump() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        List<ThreadContextImpl> activeThreadContexts = Lists.newArrayList();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            ThreadContextImpl mainThreadContext = transaction.getMainThreadContext();
            if (mainThreadContext.isActive()) {
                activeThreadContexts.add(mainThreadContext);
            }
            activeThreadContexts.addAll(transaction.getActiveAuxThreadContexts());
        }
        @Nullable
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadBean.getAllThreadIds(),
                threadBean.isObjectMonitorUsageSupported(), false);
        long currentThreadId = Thread.currentThread().getId();
        Map<Long, ThreadInfo> unmatchedThreadInfos = Maps.newHashMap();
        ThreadInfo currentThreadInfo = null;
        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo == null) {
                continue;
            }
            if (threadInfo.getThreadId() == currentThreadId) {
                currentThreadInfo = threadInfo;
            } else {
                unmatchedThreadInfos.put(threadInfo.getThreadId(), threadInfo);
            }
        }
        Map<String, TransactionThreadInfo> transactionThreadInfos = Maps.newHashMap();
        // active thread contexts for a given transaction are already sorted by age
        // so that main thread context will always appear first within a given matched transaction,
        // and its auxiliary threads will be then sorted by age
        for (ThreadContextImpl threadContext : activeThreadContexts) {
            if (!threadContext.isActive()) {
                continue;
            }
            long threadId = threadContext.getThreadId();
            ThreadInfo threadInfo = unmatchedThreadInfos.remove(threadId);
            if (threadInfo == null) {
                // this should not happen since this thread context was active before and after the
                // thread dump
                logger.warn("thread dump not captured for thread: {}", threadId);
                continue;
            }
            Transaction transaction = threadContext.getTransaction();
            String traceId = transaction.getTraceId();
            TransactionThreadInfo transactionThreadInfo = transactionThreadInfos.get(traceId);
            if (transactionThreadInfo == null) {
                transactionThreadInfo = new TransactionThreadInfo(transaction.getHeadline(),
                        transaction.getTransactionType(), transaction.getTransactionName(),
                        transaction.getDurationNanos(), transaction.getTotalCpuNanos(),
                        transactionCollector.shouldStoreSlow(transaction));
                transactionThreadInfos.put(traceId, transactionThreadInfo);
            }
            transactionThreadInfo.threadInfos.add(threadInfo);
        }
        List<ThreadDump.Transaction> transactions = Lists.newArrayList();
        for (Entry<String, TransactionThreadInfo> entry : transactionThreadInfos.entrySet()) {
            TransactionThreadInfo value = entry.getValue();
            ThreadDump.Transaction.Builder builder = ThreadDump.Transaction.newBuilder()
                    .setHeadline(value.headline)
                    .setTransactionType(value.transactionType)
                    .setTransactionName(value.transactionName)
                    .setTotalDurationNanos(value.totalDurationNanos);
            if (!NotAvailableAware.isNA(value.totalCpuNanos)) {
                builder.setTotalCpuNanos(OptionalInt64.newBuilder().setValue(value.totalCpuNanos));
            }
            if (value.shouldStoreSlow) {
                builder.setTraceId(entry.getKey());
            }
            for (ThreadInfo auxThreadInfo : value.threadInfos) {
                builder.addThread(createProtobuf(auxThreadInfo));
            }
            transactions.add(builder.build());
        }
        List<ThreadDump.Thread> unmatchedThreads = Lists.newArrayList();
        for (ThreadInfo unmatchedThreadInfo : unmatchedThreadInfos.values()) {
            unmatchedThreads.add(createProtobuf(unmatchedThreadInfo));
        }

        ThreadDump.Builder builder = ThreadDump.newBuilder()
                .addAllTransaction(transactions)
                .addAllUnmatchedThread(unmatchedThreads);
        if (currentThreadInfo != null) {
            builder.setThreadDumpingThread(createProtobuf(currentThreadInfo));
        }
        return builder.build();
    }

    private static ThreadDump.Thread createProtobuf(ThreadInfo threadInfo) {
        ThreadDump.Thread.Builder builder = ThreadDump.Thread.newBuilder()
                .setName(threadInfo.getThreadName())
                .setId(threadInfo.getThreadId())
                .setState(threadInfo.getThreadState().name());
        LockInfo lockInfo = threadInfo.getLockInfo();
        if (lockInfo != null) {
            builder.setLockInfo(ThreadDump.LockInfo.newBuilder()
                    .setIdentityHashCode(lockInfo.getIdentityHashCode())
                    .setClassName(lockInfo.getClassName()));
            long lockOwnerId = threadInfo.getLockOwnerId();
            if (lockOwnerId != -1) {
                builder.setLockOwnerId(OptionalInt64.newBuilder().setValue(lockOwnerId));
            }
        }
        List<ThreadDump.StackTraceElement.Builder> stackTraceElements = Lists.newArrayList();
        for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
            stackTraceElements.add(ThreadDump.StackTraceElement.newBuilder()
                    .setClassName(stackTraceElement.getClassName())
                    .setMethodName(Strings.nullToEmpty(stackTraceElement.getMethodName()))
                    .setFileName(Strings.nullToEmpty(stackTraceElement.getFileName()))
                    .setLineNumber(stackTraceElement.getLineNumber()));
        }
        for (MonitorInfo lockedMonitor : threadInfo.getLockedMonitors()) {
            int lockedStackDepth = lockedMonitor.getLockedStackDepth();
            if (lockedStackDepth >= 0) {
                stackTraceElements.get(lockedStackDepth)
                        .addMonitorInfo(ThreadDump.LockInfo.newBuilder()
                                .setClassName(lockedMonitor.getClassName())
                                .setIdentityHashCode(lockedMonitor.getIdentityHashCode()));
            }
        }
        for (ThreadDump.StackTraceElement.Builder stackTraceElement : stackTraceElements) {
            builder.addStackTraceElement(stackTraceElement);
        }
        return builder.build();
    }

    private static class TransactionThreadInfo {

        private final String headline;
        private final String transactionType;
        private final String transactionName;
        private final long totalDurationNanos;
        private final long totalCpuNanos;
        private final boolean shouldStoreSlow;

        private final List<ThreadInfo> threadInfos = Lists.newArrayList();

        private TransactionThreadInfo(String headline, String transactionType,
                String transactionName, long totalDurationNanos, long totalCpuNanos,
                boolean shouldStoreSlow) {
            this.headline = headline;
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            this.totalDurationNanos = totalDurationNanos;
            this.totalCpuNanos = totalCpuNanos;
            this.shouldStoreSlow = shouldStoreSlow;
        }
    }
}
