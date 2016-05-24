/*
 * Copyright 2015-2016 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

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
            if (!mainThreadContext.isCompleted()) {
                activeThreadContexts.add(mainThreadContext);
            }
            activeThreadContexts.addAll(transaction.getActiveAuxThreadContexts());
        }
        @Nullable
        ThreadInfo[] threadInfos =
                threadBean.getThreadInfo(threadBean.getAllThreadIds(), Integer.MAX_VALUE);
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
            if (threadContext.isCompleted()) {
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
                transactionThreadInfo = new TransactionThreadInfo(transaction.getTransactionType(),
                        transaction.getTransactionName(), transaction.getDurationNanos(),
                        transactionCollector.shouldStoreSlow(transaction));
                transactionThreadInfos.put(traceId, transactionThreadInfo);
            }
            transactionThreadInfo.threadInfos.add(threadInfo);
        }
        List<ThreadDump.Transaction> transactions = Lists.newArrayList();
        for (Entry<String, TransactionThreadInfo> entry : transactionThreadInfos.entrySet()) {
            TransactionThreadInfo value = entry.getValue();
            ThreadDump.Transaction.Builder builder = ThreadDump.Transaction.newBuilder()
                    .setTransactionType(value.transactionType)
                    .setTransactionName(value.transactionName)
                    .setTotalDurationNanos(value.totalDurationNanos);
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

        // sort descending by total time
        Collections.sort(transactions, new TransactionOrdering());
        // sort descending by stack trace length
        Collections.sort(unmatchedThreads, new UnmatchedThreadOrdering());

        ThreadDump.Builder builder = ThreadDump.newBuilder()
                .addAllTransaction(transactions)
                .addAllUnmatchedThread(unmatchedThreads);
        if (currentThreadInfo != null) {
            builder.setThreadDumpingThread(createProtobuf(currentThreadInfo));
        }
        return builder.build();
    }

    private ThreadDump.Thread createProtobuf(ThreadInfo threadInfo) {
        ThreadDump.Thread.Builder builder = ThreadDump.Thread.newBuilder();
        builder.setName(threadInfo.getThreadName());
        builder.setState(threadInfo.getThreadState().name());
        builder.setLockName(Strings.nullToEmpty(threadInfo.getLockName()));
        for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
            builder.addStackTraceElement(ThreadDump.StackTraceElement.newBuilder()
                    .setClassName(stackTraceElement.getClassName())
                    .setMethodName(Strings.nullToEmpty(stackTraceElement.getMethodName()))
                    .setFileName(Strings.nullToEmpty(stackTraceElement.getFileName()))
                    .setLineNumber(stackTraceElement.getLineNumber()));
        }
        return builder.build();
    }

    private static class TransactionOrdering extends Ordering<ThreadDump.Transaction> {

        @Override
        public int compare(ThreadDump.Transaction left, ThreadDump.Transaction right) {
            return Longs.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
        }
    }

    private static class UnmatchedThreadOrdering extends Ordering<ThreadDump.Thread> {
        @Override
        public int compare(ThreadDump.Thread left, ThreadDump.Thread right) {
            int result = Ints.compare(right.getStackTraceElementCount(),
                    left.getStackTraceElementCount());
            if (result == 0) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
            return result;
        }
    }

    private static class TransactionThreadInfo {

        private final String transactionType;
        private final String transactionName;
        private final long totalDurationNanos;
        private final boolean shouldStoreSlow;

        private final List<ThreadInfo> threadInfos = Lists.newArrayList();

        private TransactionThreadInfo(String transactionType, String transactionName,
                long totalDurationNanos, boolean shouldStoreSlow) {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            this.totalDurationNanos = totalDurationNanos;
            this.shouldStoreSlow = shouldStoreSlow;
        }
    }
}
