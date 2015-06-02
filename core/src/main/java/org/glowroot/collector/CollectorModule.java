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

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.JvmModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.SECONDS;

public class CollectorModule {

    private static final long FIXED_AGGREGATE_INTERVAL_SECONDS =
            Long.getLong("glowroot.internal.aggregateInterval", 60);
    private static final long FIXED_GAUGE_INTERVAL_SECONDS =
            Long.getLong("glowroot.internal.gaugeInterval", 5);

    private final TransactionCollector transactionCollector;
    private final @Nullable AggregateCollector aggregateCollector;
    private final @Nullable GaugeCollector gaugeCollector;
    private final @Nullable StackTraceCollector stackTraceCollector;

    public CollectorModule(Clock clock, Ticker ticker, JvmModule jvmModule,
            ConfigModule configModule, TraceRepository traceRepository,
            AggregateRepository aggregateRepository, GaugePointRepository gaugePointRepository,
            TransactionRegistry transactionRegistry, ScheduledExecutorService scheduledExecutor,
            boolean viewerModeEnabled) {
        ConfigService configService = configModule.getConfigService();
        if (viewerModeEnabled) {
            // ideally there should be no need for CollectorModule or TransactionModule in viewer
            // mode but viewer mode doesn't seem important enough (at this point) at least to
            // optimize the code paths and eliminate these (harmless) modules
            aggregateCollector = null;
            gaugeCollector = null;
            stackTraceCollector = null;
            transactionCollector = new NopTransactionCollector();
        } else {
            aggregateCollector = new AggregateCollector(scheduledExecutor, aggregateRepository,
                    configModule.getConfigService(), FIXED_AGGREGATE_INTERVAL_SECONDS, clock);
            gaugeCollector = new GaugeCollector(configService, gaugePointRepository,
                    jvmModule.getLazyPlatformMBeanServer(), scheduledExecutor, clock, null);
            // using fixed rate to keep gauge collections close to on the second mark
            long initialDelay = FIXED_GAUGE_INTERVAL_SECONDS
                    - (clock.currentTimeMillis() % FIXED_GAUGE_INTERVAL_SECONDS);
            gaugeCollector.scheduleAtFixedRate(initialDelay, FIXED_GAUGE_INTERVAL_SECONDS, SECONDS);
            stackTraceCollector = StackTraceCollector.create(transactionRegistry, configService,
                    scheduledExecutor);
            transactionCollector = new TransactionCollectorImpl(scheduledExecutor, configService,
                    traceRepository, aggregateCollector, clock, ticker);
        }
    }

    public TransactionCollector getTransactionCollector() {
        return transactionCollector;
    }

    public @Nullable AggregateCollector getAggregateCollector() {
        return aggregateCollector;
    }

    public long getFixedAggregateIntervalSeconds() {
        return FIXED_AGGREGATE_INTERVAL_SECONDS;
    }

    public long getFixedGaugeIntervalSeconds() {
        return FIXED_GAUGE_INTERVAL_SECONDS;
    }

    @OnlyUsedByTests
    public void close() {
        if (aggregateCollector != null) {
            aggregateCollector.close();
        }
        if (gaugeCollector != null) {
            gaugeCollector.close();
        }
        if (stackTraceCollector != null) {
            stackTraceCollector.close();
        }
    }

    private static class NopTransactionCollector implements TransactionCollector {

        @Override
        public void onCompletedTransaction(Transaction transaction) {}

        @Override
        public void storePartialTrace(Transaction transaction) {}

        @Override
        public Collection<Transaction> getPendingTransactions() {
            return ImmutableList.of();
        }

        @Override
        public boolean shouldStore(Transaction transaction) {
            return false;
        }
    }
}
