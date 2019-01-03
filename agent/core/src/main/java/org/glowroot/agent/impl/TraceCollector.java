/*
 * Copyright 2011-2019 the original author or authors.
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.TransactionConfig.SlowThresholdOverride;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TraceCollector {

    private static final Logger logger = LoggerFactory.getLogger(TraceCollector.class);

    // back pressure on writing captured data to disk/network
    private static final int PENDING_LIMIT = 50;

    private final ExecutorService dedicatedExecutor;
    private final Collector collector;
    private final Clock clock;
    private final Ticker ticker;
    // covers normal complete, partial complete and partial incomplete separately
    private final BlockingQueue<PendingTrace> pendingTraces =
            Queues.newLinkedBlockingQueue(PENDING_LIMIT * 3);
    private final AtomicInteger normalCompletePendingCount = new AtomicInteger();
    private final AtomicInteger partialCompletePendingCount = new AtomicInteger();
    private final AtomicInteger partialIncompletePendingCount = new AtomicInteger();

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(TraceCollector.class);

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private Map<String, SlowThresholdOverridesForType> slowThresholdOverrides = ImmutableMap.of();
    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private long defaultSlowThresholdNanos;

    private volatile boolean closed;

    public TraceCollector(final ConfigService configService, Collector collector, Clock clock,
            Ticker ticker) {
        this.collector = collector;
        this.clock = clock;
        this.ticker = ticker;
        dedicatedExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Trace-Collector"));
        dedicatedExecutor.execute(new TraceCollectorLoop());
        configService.addConfigListener(new UpdateLocalConfig(configService));
    }

    public boolean shouldStoreSlow(Transaction transaction) {
        if (transaction.isPartiallyStored()) {
            return true;
        }
        long durationNanos = transaction.getDurationNanos();
        // check if trace-specific store threshold was set
        long slowThresholdMillis = transaction.getSlowThresholdMillisOverride();
        if (slowThresholdMillis != Transaction.USE_GENERAL_STORE_THRESHOLD) {
            return durationNanos >= MILLISECONDS.toNanos(slowThresholdMillis);
        }
        // check if there is a matching transaction type / transaction name specific slow threshold
        if (!slowThresholdOverrides.isEmpty()) {
            SlowThresholdOverridesForType slowThresholdOverrideForType =
                    slowThresholdOverrides.get(transaction.getTransactionType());
            if (slowThresholdOverrideForType != null) {
                Long slowThresholdNanos =
                        slowThresholdOverrideForType.thresholdNanos()
                                .get(transaction.getTransactionName());
                if (slowThresholdNanos != null) {
                    return durationNanos >= slowThresholdNanos;
                }
                slowThresholdNanos = slowThresholdOverrideForType.defaultThresholdNanos();
                if (slowThresholdNanos != null) {
                    return durationNanos >= slowThresholdNanos;
                }
            }
        }
        // fall back to default slow trace threshold
        return durationNanos >= defaultSlowThresholdNanos;
    }

    public boolean shouldStoreError(Transaction transaction) {
        return transaction.getErrorMessage() != null;
    }

    public Collection<Transaction> getPendingTransactions() {
        List<Transaction> pendingTransactions = Lists.newArrayList();
        for (PendingTrace pendingTrace : pendingTraces) {
            pendingTransactions.add(pendingTrace.transaction());
        }
        return pendingTransactions;
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to collector thread
        dedicatedExecutor.shutdownNow();
        if (!dedicatedExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    void collectTrace(Transaction transaction) {
        boolean slow = shouldStoreSlow(transaction);
        if (!slow && !shouldStoreError(transaction)) {
            return;
        }
        // don't need to worry about race condition since only ever called from a single thread
        if (transaction.isPartiallyStored()
                && partialCompletePendingCount.get() >= PENDING_LIMIT) {
            backPressureLogger.warn("not storing a completed (and once partial) trace because of an"
                    + " excessive backlog of {} completed (and once partial) traces already waiting"
                    + " to be stored", PENDING_LIMIT);
            return;
        } else if (!transaction.isPartiallyStored()
                && normalCompletePendingCount.get() >= PENDING_LIMIT) {
            backPressureLogger.warn("not storing a completed trace because of an excessive backlog"
                    + " of {} completed traces already waiting to be stored", PENDING_LIMIT);
            return;
        }
        PendingTrace pendingTransaction = ImmutablePendingTrace.builder()
                .transaction(transaction)
                .slow(slow)
                .partial(false)
                .build();
        if (!pendingTraces.offer(pendingTransaction)) {
            // this should never happen
            backPressureLogger.warn("not storing a trace because of an excessive backlog of {}"
                    + " traces already waiting to be stored", PENDING_LIMIT * 3);
        }
    }

    public void storePartialTrace(Transaction transaction) {
        // don't need to worry about race condition since only ever called from a single thread
        if (partialIncompletePendingCount.get() >= PENDING_LIMIT) {
            backPressureLogger.warn("not storing a partial trace because of an excessive backlog of"
                    + " {} partial traces already waiting to be stored", PENDING_LIMIT);
            return;
        }
        PendingTrace pendingTransaction = ImmutablePendingTrace.builder()
                .transaction(transaction)
                .slow(false)
                .partial(true)
                .build();
        if (!pendingTraces.offer(pendingTransaction)) {
            // this should never happen
            backPressureLogger.warn("not storing a trace because of an excessive backlog of {}"
                    + " traces already waiting to be stored", PENDING_LIMIT * 3);
        }
    }

    private class UpdateLocalConfig implements ConfigListener {

        private final ConfigService configService;

        private UpdateLocalConfig(ConfigService configService) {
            this.configService = configService;
        }

        @Override
        public void onChange() {
            TransactionConfig transactionConfig = configService.getTransactionConfig();
            Map<String, SlowThresholdOverridesForTypeBuilder> slowThresholdOverrides =
                    Maps.newHashMap();
            for (SlowThresholdOverride slowThresholdOverride : transactionConfig
                    .slowThresholdOverrides()) {
                String transactionType = slowThresholdOverride.transactionType();
                SlowThresholdOverridesForTypeBuilder slowThresholdOverrideForType =
                        slowThresholdOverrides.get(transactionType);
                if (slowThresholdOverrideForType == null) {
                    slowThresholdOverrideForType = new SlowThresholdOverridesForTypeBuilder();
                    slowThresholdOverrides.put(transactionType, slowThresholdOverrideForType);
                }
                String transactionName = slowThresholdOverride.transactionName();
                long thresholdNanos = MILLISECONDS.toNanos(slowThresholdOverride.thresholdMillis());
                if (transactionName.isEmpty()) {
                    slowThresholdOverrideForType.defaultThresholdNanos = thresholdNanos;
                } else {
                    slowThresholdOverrideForType.thresholdNanos.put(transactionName,
                            thresholdNanos);
                }
            }
            Map<String, SlowThresholdOverridesForType> builder = Maps.newHashMap();
            for (Map.Entry<String, SlowThresholdOverridesForTypeBuilder> entry : slowThresholdOverrides
                    .entrySet()) {
                builder.put(entry.getKey(), entry.getValue().toImmutable());
            }
            TraceCollector.this.slowThresholdOverrides = ImmutableMap.copyOf(builder);
            defaultSlowThresholdNanos =
                    MILLISECONDS.toNanos(transactionConfig.slowThresholdMillis());
        }
    }

    private class TraceCollectorLoop implements Runnable {

        @Override
        public void run() {
            while (!closed) {
                try {
                    PendingTrace pendingTrace = pendingTraces.take();
                    if (pendingTrace.partial()) {
                        collectPartial(pendingTrace.transaction());
                    } else {
                        collectCompleted(pendingTrace.transaction(), pendingTrace.slow());
                    }
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                    logger.debug(e.getMessage(), e);
                } catch (Throwable e) {
                    // log and continue processing
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void collectPartial(Transaction transaction) throws Exception {
            TraceReader traceReader = TraceCreator.createTraceReaderForPartial(transaction,
                    clock.currentTimeMillis(), ticker.read());
            // one last check if transaction has completed
            if (!transaction.isCompleted()) {
                transaction.setPartiallyStored();
                collector.collectTrace(traceReader);
            }
        }

        private void collectCompleted(Transaction transaction, boolean slow) throws Exception {
            TraceReader traceReader = TraceCreator.createTraceReaderForCompleted(
                    transaction, slow);
            collector.collectTrace(traceReader);
        }
    }

    @Value.Immutable
    public interface PendingTrace {
        Transaction transaction();
        boolean slow();
        boolean partial();
    }

    @Value.Immutable
    interface SlowThresholdOverridesForType {
        @Nullable
        Long defaultThresholdNanos();
        Map<String, Long> thresholdNanos(); // key is transaction name
    }

    // need separate builder type to avoid exception in case of duplicate
    // transactionType/transactionName pair
    private static class SlowThresholdOverridesForTypeBuilder {

        private @Nullable Long defaultThresholdNanos;
        private Map<String, Long> thresholdNanos = Maps.newHashMap();

        private SlowThresholdOverridesForType toImmutable() {
            return ImmutableSlowThresholdOverridesForType.builder()
                    .defaultThresholdNanos(defaultThresholdNanos)
                    .putAllThresholdNanos(thresholdNanos)
                    .build();
        }
    }
}
