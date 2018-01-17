/*
 * Copyright 2011-2018 the original author or authors.
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.TransactionConfig;
import org.glowroot.agent.config.TransactionConfig.SlowThreshold;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TransactionCollector {

    private static final Logger logger = LoggerFactory.getLogger(TransactionCollector.class);

    // back pressure on trace collection
    private static final int PENDING_LIMIT = 100;

    private final ExecutorService dedicatedExecutor;
    private final Collector collector;
    private final Aggregator aggregator;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Transaction> pendingTransactions = Sets.newCopyOnWriteArraySet();

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(TransactionCollector.class);

    private volatile Map<String, SlowThresholdsForType> slowThresholds = ImmutableMap.of();
    private volatile long defaultSlowThresholdNanos;

    public TransactionCollector(final ConfigService configService, Collector collector,
            Aggregator aggregator, Clock clock, Ticker ticker) {
        this.collector = collector;
        this.aggregator = aggregator;
        this.clock = clock;
        this.ticker = ticker;
        dedicatedExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Trace-Collector"));
        configService.addConfigListener(new UpdateSlowThresholds(configService));
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
        if (!slowThresholds.isEmpty()) {
            SlowThresholdsForType slowThresholdForType =
                    slowThresholds.get(transaction.getTransactionType());
            if (slowThresholdForType != null) {
                Long slowThresholdNanos =
                        slowThresholdForType.thresholdNanos().get(transaction.getTransactionName());
                if (slowThresholdNanos != null) {
                    return durationNanos >= slowThresholdNanos;
                }
                slowThresholdNanos = slowThresholdForType.defaultThresholdNanos();
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
        return pendingTransactions;
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        dedicatedExecutor.shutdown();
        if (!dedicatedExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    void onCompletedTransaction(final Transaction transaction) {
        // capture time is calculated by the aggregator because it depends on monotonically
        // increasing capture times so it can flush aggregates without concern for new data
        // arriving with a prior capture time
        long captureTime = aggregator.add(transaction);
        final boolean slow = shouldStoreSlow(transaction);
        if (!slow && !shouldStoreError(transaction)) {
            return;
        }
        // limit doesn't apply to transactions that were already (partially) stored to make sure
        // they don't get left out in case they cause an avalanche of slowness
        if (pendingTransactions.size() >= PENDING_LIMIT && !transaction.isPartiallyStored()) {
            backPressureLogger.warn("not storing a trace because of an excessive backlog of {}"
                    + " traces already waiting to be stored", PENDING_LIMIT);
            return;
        }
        pendingTransactions.add(transaction);

        // this need to be called inside the transaction thread
        transaction.onCompleteWillStoreTrace(captureTime);

        // transaction is ended, so Executor Plugin won't tie this async work to the transaction
        // (which is good)
        dedicatedExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    TraceReader traceReader =
                            TraceCreator.createTraceReaderForCompleted(transaction, slow);
                    collector.collectTrace(traceReader);
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                } finally {
                    pendingTransactions.remove(transaction);
                }
            }
        });
    }

    // no need to throttle partial trace storage since throttling is handled upstream by using a
    // single thread executor in PartialTraceStorageWatcher
    public void storePartialTrace(Transaction transaction) {
        try {
            TraceReader traceReader = TraceCreator.createTraceReaderForPartial(transaction,
                    clock.currentTimeMillis(), ticker.read());
            // one last check if transaction has completed
            if (!transaction.isCompleted()) {
                transaction.setPartiallyStored();
                collector.collectTrace(traceReader);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private class UpdateSlowThresholds implements ConfigListener {

        private final ConfigService configService;

        private UpdateSlowThresholds(ConfigService configService) {
            this.configService = configService;
        }

        @Override
        public void onChange() {
            TransactionConfig transactionConfig = configService.getTransactionConfig();
            Map<String, SlowThresholdsForTypeBuilder> slowThresholds = Maps.newHashMap();
            for (SlowThreshold slowThreshold : transactionConfig.slowThresholds()) {
                String transactionType = slowThreshold.transactionType();
                SlowThresholdsForTypeBuilder slowThresholdForType =
                        slowThresholds.get(transactionType);
                if (slowThresholdForType == null) {
                    slowThresholdForType = new SlowThresholdsForTypeBuilder();
                    slowThresholds.put(transactionType, slowThresholdForType);
                }
                String transactionName = slowThreshold.transactionName();
                long thresholdNanos = MILLISECONDS.toNanos(slowThreshold.thresholdMillis());
                if (transactionName.isEmpty()) {
                    slowThresholdForType.defaultThresholdNanos = thresholdNanos;
                } else {
                    slowThresholdForType.thresholdNanos.put(transactionName, thresholdNanos);
                }
            }
            Map<String, SlowThresholdsForType> builder = Maps.newHashMap();
            for (Map.Entry<String, SlowThresholdsForTypeBuilder> entry : slowThresholds
                    .entrySet()) {
                builder.put(entry.getKey(), entry.getValue().toImmutable());
            }
            TransactionCollector.this.slowThresholds = ImmutableMap.copyOf(builder);
            defaultSlowThresholdNanos =
                    MILLISECONDS.toNanos(transactionConfig.slowThresholdMillis());
        }
    }

    @Value.Immutable
    interface SlowThresholdsForType {
        @Nullable
        Long defaultThresholdNanos();
        Map<String, Long> thresholdNanos(); // key is transaction name
    }

    // need separate builder type to avoid exception in case of duplicate
    // transactionType/transactionName pair
    private static class SlowThresholdsForTypeBuilder {

        private @Nullable Long defaultThresholdNanos;
        private Map<String, Long> thresholdNanos = Maps.newHashMap();

        private SlowThresholdsForType toImmutable() {
            return ImmutableSlowThresholdsForType.builder()
                    .defaultThresholdNanos(defaultThresholdNanos)
                    .putAllThresholdNanos(thresholdNanos)
                    .build();
        }
    }
}
