/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.init;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class StackTraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceCollector.class);

    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final ScheduledExecutorService scheduledExecutor;
    private final Random random;

    private volatile long remainingInInterval;

    private volatile @MonotonicNonNull InternalRunnable currentInternalRunnable;

    public static StackTraceCollector create(TransactionRegistry transactionRegistry,
            ConfigService configService, ScheduledExecutorService scheduledExecutor,
            Random random) {
        final StackTraceCollector stackTraceCollector = new StackTraceCollector(transactionRegistry,
                configService, scheduledExecutor, random);
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                stackTraceCollector.updateScheduleIfNeeded();
            }
        });
        return stackTraceCollector;
    }

    private StackTraceCollector(TransactionRegistry transactionRegistry,
            final ConfigService configService, ScheduledExecutorService scheduledExecutor,
            Random random) {
        this.transactionRegistry = transactionRegistry;
        this.configService = configService;
        this.scheduledExecutor = scheduledExecutor;
        this.random = random;
    }

    private void updateScheduleIfNeeded() {
        int intervalMillis = configService.getTransactionConfig().profilingIntervalMillis();
        if (currentInternalRunnable == null
                || intervalMillis != currentInternalRunnable.intervalMillis) {
            if (currentInternalRunnable != null) {
                currentInternalRunnable.cancel();
            }
            if (intervalMillis > 0) {
                currentInternalRunnable = new InternalRunnable(intervalMillis);
                currentInternalRunnable.scheduleFirst();
            }
        }
    }

    @OnlyUsedByTests
    void close() {
        if (currentInternalRunnable != null) {
            currentInternalRunnable.cancel();
        }
    }

    // separate runnable each time intervalMillis changes makes it easy to reason that the previous
    // one is cleanly stopped when intervalMillis changes
    private class InternalRunnable implements Runnable {

        private final int intervalMillis;

        private final AtomicBoolean closing = new AtomicBoolean();

        private volatile @Nullable Future<?> currentFuture;

        private InternalRunnable(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        @Override
        public void run() {
            try {
                runInternal();
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
            try {
                scheduleNext();
            } catch (Throwable t) {
                // this is not good, no further stack trace will be captured
                logger.error(t.getMessage(), t);
            }
        }

        private void scheduleFirst() {
            long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
            synchronized (closing) {
                // this is to guard against sending command to closed executor
                if (closing.get()) {
                    return;
                }
                currentFuture = scheduledExecutor.schedule(this, randomDelayFromIntervalStart,
                        MILLISECONDS);
            }
            remainingInInterval = intervalMillis - randomDelayFromIntervalStart;
        }

        private void scheduleNext() {
            long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
            synchronized (closing) {
                // this is to guard against sending command to closed executor
                if (closing.get()) {
                    return;
                }
                scheduledExecutor.schedule(this, remainingInInterval + randomDelayFromIntervalStart,
                        MILLISECONDS);
            }
            remainingInInterval = intervalMillis - randomDelayFromIntervalStart;
        }

        private void runInternal() {
            if (closing.get()) {
                return;
            }
            List<Transaction> transactions =
                    ImmutableList.copyOf(transactionRegistry.getTransactions());
            if (transactions.isEmpty()) {
                return;
            }
            List<ThreadContextImpl> activeThreadContexts =
                    Lists.newArrayListWithCapacity(2 * transactions.size());
            for (int i = 0; i < transactions.size(); i++) {
                Transaction transaction = transactions.get(i);
                ThreadContextImpl mainThreadContext = transaction.getMainThreadContext();
                if (!mainThreadContext.isCompleted()) {
                    activeThreadContexts.add(mainThreadContext);
                }
                for (ThreadContextImpl auxThreadContext : transaction.getAuxThreadContexts()) {
                    if (!auxThreadContext.isCompleted()) {
                        activeThreadContexts.add(auxThreadContext);
                    }
                }
            }
            captureStackTraces(activeThreadContexts);
        }

        private void captureStackTraces(List<ThreadContextImpl> threadContexts) {
            long[] threadIds = new long[threadContexts.size()];
            for (int i = 0; i < threadContexts.size(); i++) {
                threadIds[i] = threadContexts.get(i).getThreadId();
            }
            @Nullable
            ThreadInfo[] threadInfos =
                    ManagementFactory.getThreadMXBean().getThreadInfo(threadIds, Integer.MAX_VALUE);
            int limit = configService.getAdvancedConfig().maxStackTraceSamplesPerTransaction();
            for (int i = 0; i < threadContexts.size(); i++) {
                ThreadContextImpl threadContext = threadContexts.get(i);
                ThreadInfo threadInfo = threadInfos[i];
                if (threadInfo != null) {
                    threadContext.captureStackTrace(threadInfo, limit);
                }
            }
        }

        private void cancel() {
            synchronized (closing) {
                closing.set(true);
            }
            if (currentFuture != null) {
                currentFuture.cancel(false);
            }
        }
    }
}
