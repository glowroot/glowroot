/*
 * Copyright 2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.Transaction;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

import static com.google.common.base.Preconditions.checkNotNull;

class ThreadDumpService {

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;

    ThreadDumpService(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
    }

    ThreadDump getThreadDump() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<Long, Transaction> transactionsBefore = Maps.newHashMap();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            transactionsBefore.put(transaction.getThreadId(), transaction);
        }
        ThreadInfo[] threadInfos =
                threadBean.getThreadInfo(threadBean.getAllThreadIds(), Integer.MAX_VALUE);
        final Map<Long, Transaction> matchedTransactions = Maps.newHashMap();
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            if (transactionsBefore.get(transaction.getThreadId()) == transaction) {
                matchedTransactions.put(transaction.getThreadId(), transaction);
            }
        }
        long currentThreadId = Thread.currentThread().getId();
        ThreadInfo currentThreadInfo = null;
        List<ThreadInfo> matchedThreadInfos = Lists.newArrayList();
        List<ThreadInfo> unmatchedThreadInfos = Lists.newArrayList();
        for (ThreadInfo threadInfo : threadInfos) {
            long threadId = threadInfo.getThreadId();
            if (threadId == currentThreadId) {
                currentThreadInfo = threadInfo;
            } else if (matchedTransactions.containsKey(threadId)) {
                matchedThreadInfos.add(threadInfo);
            } else {
                unmatchedThreadInfos.add(threadInfo);
            }
        }
        // sort descending by total time
        Collections.sort(matchedThreadInfos, new MatchedThreadInfoOrdering(matchedTransactions));
        // sort descending by stack trace length
        Collections.sort(unmatchedThreadInfos, new UnmatchedThreadInfoOrdering());

        ThreadDump.Builder builder = ThreadDump.newBuilder();
        for (ThreadInfo threadInfo : matchedThreadInfos) {
            Transaction matchedTransaction = matchedTransactions.get(threadInfo.getThreadId());
            builder.addMatchedThread(createThreadInfo(threadInfo, matchedTransaction));
        }
        for (ThreadInfo threadInfo : unmatchedThreadInfos) {
            builder.addUnmatchedThread(createThreadInfo(threadInfo, null));
        }
        if (currentThreadInfo != null) {
            Transaction matchedTransaction =
                    matchedTransactions.get(currentThreadInfo.getThreadId());
            builder.setThreadDumpingThread(
                    createThreadInfo(currentThreadInfo, matchedTransaction));
        }
        return builder.build();
    }

    private ThreadDump.ThreadInfo createThreadInfo(ThreadInfo threadInfo,
            @Nullable Transaction matchedTransaction) {
        ThreadDump.ThreadInfo.Builder builder = ThreadDump.ThreadInfo.newBuilder();
        builder.setName(threadInfo.getThreadName());
        builder.setState(threadInfo.getThreadState().name());
        builder.setLockName(Strings.nullToEmpty(threadInfo.getLockName()));
        for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
            builder.addStackTraceElement(
                    ThreadDump.StackTraceElement.newBuilder()
                            .setClassName(stackTraceElement.getClassName())
                            .setMethodName(Strings.nullToEmpty(stackTraceElement.getMethodName()))
                            .setFileName(Strings.nullToEmpty(stackTraceElement.getFileName()))
                            .setLineNumber(stackTraceElement.getLineNumber()));
        }

        if (matchedTransaction != null) {
            builder.setTransactionType(matchedTransaction.getTransactionType());
            builder.setTransactionName(matchedTransaction.getTransactionName());
            builder.setTransactionTotalNanos(matchedTransaction.getDurationNanos());
            if (transactionCollector.shouldStoreSlow(matchedTransaction)) {
                builder.setTraceId(matchedTransaction.getId());
            }
        }
        return builder.build();
    }

    private static class MatchedThreadInfoOrdering extends Ordering<ThreadInfo> {

        private final Map<Long, Transaction> matchedTransactions;

        private MatchedThreadInfoOrdering(Map<Long, Transaction> matchedTransactions) {
            this.matchedTransactions = matchedTransactions;
        }

        @Override
        public int compare(ThreadInfo left, ThreadInfo right) {
            Transaction leftTransaction = matchedTransactions.get(left.getThreadId());
            Transaction rightTransaction = matchedTransactions.get(right.getThreadId());
            // left and right are from matchedThreadInfos so have corresponding transactions
            checkNotNull(leftTransaction);
            checkNotNull(rightTransaction);
            return Longs.compare(rightTransaction.getDurationNanos(),
                    leftTransaction.getDurationNanos());
        }
    }

    private static class UnmatchedThreadInfoOrdering extends Ordering<ThreadInfo> {
        @Override
        public int compare(ThreadInfo left, ThreadInfo right) {
            if (left.getThreadId() == Thread.currentThread().getId()) {
                return 1;
            } else if (right.getThreadId() == Thread.currentThread().getId()) {
                return -1;
            }
            int result = Ints.compare(right.getStackTrace().length, left.getStackTrace().length);
            if (result == 0) {
                return left.getThreadName().compareToIgnoreCase(right.getThreadName());
            }
            return result;
        }
    }
}
