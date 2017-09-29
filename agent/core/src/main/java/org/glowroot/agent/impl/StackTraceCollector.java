/*
 * Copyright 2011-2017 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

public class StackTraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceCollector.class);

    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final Random random;

    private final InternalRunnable runnable;
    private final Thread processingThread;

    public StackTraceCollector(TransactionRegistry transactionRegistry,
            final ConfigService configService, Random random) {
        this.transactionRegistry = transactionRegistry;
        this.configService = configService;
        this.random = random;

        runnable = new InternalRunnable();
        // dedicated thread to give best chance of consistent stack trace capture
        // this is important for unit tests, but seems good for real usage as well
        processingThread = new Thread(runnable);
        processingThread.setDaemon(true);
        processingThread.setName("Glowroot-Stack-Trace-Collector");
        processingThread.start();

        configService.addConfigListener(new ConfigListener() {
            private int currIntervalMillis;
            @Override
            public void onChange() {
                int intervalMillis = configService.getTransactionConfig().profilingIntervalMillis();
                if (intervalMillis != currIntervalMillis) {
                    currIntervalMillis = intervalMillis;
                    checkNotNull(processingThread);
                    processingThread.interrupt();
                }
            }
        });
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        runnable.closed = true;
        processingThread.interrupt();
        processingThread.join();
    }

    static void captureStackTraces(List<ThreadContextImpl> threadContexts,
            ConfigService configService) {
        if (threadContexts.isEmpty()) {
            // critical not to call ThreadMXBean.getThreadInfo() with empty id list
            // see https://bugs.openjdk.java.net/browse/JDK-8074368
            return;
        }
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

    private class InternalRunnable implements Runnable {

        private volatile boolean closed;

        @Override
        public void run() {
            // delay for first
            long remainingInInterval = 0;
            while (!closed) {
                int intervalMillis = configService.getTransactionConfig().profilingIntervalMillis();
                if (intervalMillis <= 0) {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        // probably interrupt from config listener (see above)
                        logger.debug(e.getMessage(), e);
                        // re-start loop
                        remainingInInterval = 0;
                        continue;
                    }
                }
                long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
                try {
                    Thread.sleep(remainingInInterval + randomDelayFromIntervalStart);
                } catch (InterruptedException e) {
                    // probably interrupt from config listener (see above)
                    logger.debug(e.getMessage(), e);
                    // re-start loop
                    remainingInInterval = 0;
                    continue;
                }
                remainingInInterval = intervalMillis - randomDelayFromIntervalStart;
                try {
                    runInternal();
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        private void runInternal() {
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
                if (mainThreadContext.isActive()) {
                    activeThreadContexts.add(mainThreadContext);
                }
                activeThreadContexts.addAll(transaction.getActiveAuxThreadContexts());
            }
            captureStackTraces(activeThreadContexts, configService);
        }
    }
}
