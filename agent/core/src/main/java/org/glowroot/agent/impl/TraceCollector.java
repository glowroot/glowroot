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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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
    private static final int PENDING_LIMIT = 100;

    private final ExecutorService dedicatedExecutor;
    private final Collector collector;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Transaction> pendingTransactions = Sets.newCopyOnWriteArraySet();

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(TraceCollector.class);

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private Map<String, SlowThresholdOverridesForType> slowThresholdOverrides = ImmutableMap.of();
    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private long defaultSlowThresholdNanos;

    public TraceCollector(final ConfigService configService, Collector collector, Clock clock,
            Ticker ticker) {
        this.collector = collector;
        this.clock = clock;
        this.ticker = ticker;
        dedicatedExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Trace-Collector"));
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
        // check if there is a matching transaction type / transaction name / user specific slow threshold
        if (!slowThresholdOverrides.isEmpty()) {
            SlowThresholdOverridesForType slowThresholdOverrideForType =
                    slowThresholdOverrides.get(transaction.getTransactionType());
            if (slowThresholdOverrideForType != null) {
                String transactionName = transaction.getTransactionName();
                String user = transaction.getUser();
                Long slowThresholdNanos;
                ImmutableSlowThresholdOverrideFilter filter;
                // check transaction name & user
                if (!user.isEmpty()) {
                    filter = ImmutableSlowThresholdOverrideFilter.of(transactionName, user);
                    slowThresholdNanos = slowThresholdOverrideForType.thresholdNanos().get(filter);
                    if (slowThresholdNanos != null) {
                        return durationNanos >= slowThresholdNanos;
                    }
                }
                // check transaction name only
                filter = ImmutableSlowThresholdOverrideFilter.of(transactionName, "");
                slowThresholdNanos = slowThresholdOverrideForType.thresholdNanos().get(filter);
                // check user only
                if (!user.isEmpty()) {
                    filter = ImmutableSlowThresholdOverrideFilter.of("", user);
                    slowThresholdNanos = minNonNull(
                            slowThresholdOverrideForType.thresholdNanos().get(filter),
                            slowThresholdNanos);
                }
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

    private Long minNonNull(Long l1, Long l2) {
        if (l1 != null && l2 != null) {
            return Math.min(l1, l2);
        } else if (l1 != null) {
            return l1;
        } else if (l2 != null) {
            return l2;
        } else {
            return null;
        }
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

    void collectTrace(final Transaction transaction) {
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
                String user = slowThresholdOverride.user();
                long thresholdNanos = MILLISECONDS.toNanos(slowThresholdOverride.thresholdMillis());
                if (transactionName.isEmpty() && user.isEmpty()) {
                    slowThresholdOverrideForType.defaultThresholdNanos = thresholdNanos;
                } else {
                    ImmutableSlowThresholdOverrideFilter filter = ImmutableSlowThresholdOverrideFilter.of(transactionName, user);
                    slowThresholdOverrideForType.thresholdNanos.put(filter, thresholdNanos);
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

    @Value.Immutable
    @Value.Style(allParameters = true)
    interface SlowThresholdOverrideFilter {
        String transactionName();
        String user();
    }

    @Value.Immutable
    @Value.Style(deepImmutablesDetection = true)
    interface SlowThresholdOverridesForType {
        @Nullable
        Long defaultThresholdNanos();
        Map<SlowThresholdOverrideFilter, Long> thresholdNanos();
    }

    // need separate builder type to avoid exception in case of duplicate pair
    private static class SlowThresholdOverridesForTypeBuilder {

        private @Nullable Long defaultThresholdNanos;
        private Map<SlowThresholdOverrideFilter, Long> thresholdNanos = Maps.newHashMap();

        private SlowThresholdOverridesForType toImmutable() {
            return ImmutableSlowThresholdOverridesForType.builder()
                    .defaultThresholdNanos(defaultThresholdNanos)
                    .putAllThresholdNanos(thresholdNanos)
                    .build();
        }
    }
}
