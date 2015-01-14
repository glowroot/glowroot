/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.collector;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class StackTraceCollector implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceCollector.class);

    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final ScheduledExecutorService scheduledExecutor;

    private volatile boolean currentEnabled;
    private volatile int currentIntervalMillis;
    private volatile @Nullable Future<?> currentFuture;

    public static StackTraceCollector create(TransactionRegistry transactionRegistry,
            ConfigService configService, ScheduledExecutorService scheduledExecutor) {
        final StackTraceCollector stackTraceCollector =
                new StackTraceCollector(transactionRegistry, configService, scheduledExecutor);
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                stackTraceCollector.updateScheduleIfNeeded();
            }
        });
        stackTraceCollector.updateScheduleIfNeeded();
        return stackTraceCollector;
    }

    private StackTraceCollector(TransactionRegistry transactionRegistry,
            final ConfigService configService, ScheduledExecutorService scheduledExecutor) {
        this.transactionRegistry = transactionRegistry;
        this.configService = configService;
        this.scheduledExecutor = scheduledExecutor;
    }

    @Override
    public void run() {
        try {
            runInternal();
        } catch (Throwable t) {
            // log and return successfully so it will continue to run
            logger.error(t.getMessage(), t);
        }
    }

    private void updateScheduleIfNeeded() {
        boolean newEnabled = configService.getProfilingConfig().enabled();
        int newIntervalMillis = configService.getProfilingConfig().intervalMillis();
        if (newEnabled != currentEnabled || newIntervalMillis != currentIntervalMillis) {
            if (currentFuture != null) {
                currentFuture.cancel(false);
            }
            if (newEnabled) {
                currentFuture = scheduledExecutor.scheduleAtFixedRate(this, newIntervalMillis,
                        newIntervalMillis, MILLISECONDS);
            }
            currentEnabled = newEnabled;
            currentIntervalMillis = newIntervalMillis;
        }
    }

    private void runInternal() {
        List<Transaction> transactions =
                ImmutableList.copyOf(transactionRegistry.getTransactions());
        if (transactions.isEmpty()) {
            return;
        }
        long[] threadIds = new long[transactions.size()];
        for (int i = 0; i < transactions.size(); i++) {
            threadIds[i] = transactions.get(i).getThreadId();
        }
        ThreadInfo[] threadInfos =
                ManagementFactory.getThreadMXBean().getThreadInfo(threadIds, Integer.MAX_VALUE);
        for (int i = 0; i < transactions.size(); i++) {
            transactions.get(i).captureStackTrace(threadInfos[i],
                    configService.getAdvancedConfig().maxStackTraceSamplesPerTransaction());
        }
    }

    @OnlyUsedByTests
    void close() {
        if (currentFuture != null) {
            currentFuture.cancel(false);
        }
    }
}
