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

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.UserProfileScheduler;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

class StackTraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceCollector.class);

    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final Random random;

    private final InternalRunnable runnable;
    private final Thread processingThread;

    StackTraceCollector(TransactionRegistry transactionRegistry, final ConfigService configService,
            Random random) {
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
    void close() {
        runnable.closing.set(true);
        processingThread.interrupt();
    }

    private class InternalRunnable implements Runnable {

        private final AtomicBoolean closing = new AtomicBoolean();

        @Override
        public void run() {
            // delay for first
            long remainingInInterval = 0;
            while (true) {
                int intervalMillis = configService.getTransactionConfig().profilingIntervalMillis();
                if (intervalMillis <= 0) {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        logger.debug(e.getMessage(), e);
                        // only terminate if closing
                        if (closing.get()) {
                            return;
                        }
                        // re-start loop
                        remainingInInterval = 0;
                        continue;
                    }
                }
                long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
                try {
                    Thread.sleep(remainingInInterval + randomDelayFromIntervalStart);
                } catch (InterruptedException e) {
                    logger.debug(e.getMessage(), e);
                    // only terminate if closing
                    if (closing.get()) {
                        return;
                    }
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
                if (!mainThreadContext.isCompleted()) {
                    activeThreadContexts.add(mainThreadContext);
                }
                activeThreadContexts.addAll(transaction.getActiveAuxThreadContexts());
            }
            UserProfileScheduler.captureStackTraces(activeThreadContexts, configService);
        }
    }
}
