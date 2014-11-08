/*
 * Copyright 2011-2014 the original author or authors.
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

import java.util.concurrent.ScheduledExecutorService;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.JvmModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionRegistry;

import static java.util.concurrent.TimeUnit.SECONDS;

public class CollectorModule {

    private static final long fixedAggregateIntervalSeconds;
    private static final long fixedGaugeIntervalSeconds;

    static {
        fixedAggregateIntervalSeconds =
                Long.getLong("glowroot.internal.collector.aggregateInterval", 300);
        fixedGaugeIntervalSeconds =
                Long.getLong("glowroot.internal.collector.gaugeInterval", 5);
    }

    private final TransactionCollectorImpl transactionCollector;
    @Nullable
    private final AggregateCollector aggregateCollector;
    @Nullable
    private final GaugeCollector gaugeCollector;
    @Nullable
    private final StackTraceCollector stackTraceCollector;

    public CollectorModule(Clock clock, Ticker ticker, JvmModule jvmModule,
            ConfigModule configModule, TraceRepository traceRepository,
            AggregateRepository aggregateRepository, GaugePointRepository gaugePointRepository,
            TransactionRegistry transactionRegistry, ScheduledExecutorService scheduledExecutor,
            boolean viewerModeEnabled) {
        ConfigService configService = configModule.getConfigService();
        if (viewerModeEnabled) {
            aggregateCollector = null;
            gaugeCollector = null;
            stackTraceCollector = null;
        } else {
            aggregateCollector = new AggregateCollector(scheduledExecutor, aggregateRepository,
                    clock, fixedAggregateIntervalSeconds);
            gaugeCollector = new GaugeCollector(configService, gaugePointRepository,
                    jvmModule.getLazyPlatformMBeanServer(), clock);
            // using fixed rate to keep gauge collections close to on the second mark
            long initialDelay = fixedGaugeIntervalSeconds
                    - (clock.currentTimeMillis() % fixedGaugeIntervalSeconds);
            gaugeCollector.scheduleAtFixedRate(scheduledExecutor, initialDelay,
                    fixedGaugeIntervalSeconds, SECONDS);
            stackTraceCollector = StackTraceCollector.create(transactionRegistry, configService,
                    scheduledExecutor);
        }
        // TODO should be no need for transaction collector in viewer mode
        transactionCollector = new TransactionCollectorImpl(scheduledExecutor, configService,
                traceRepository, aggregateCollector, clock, ticker);
    }

    public TransactionCollectorImpl getTransactionCollector() {
        return transactionCollector;
    }

    public long getFixedAggregateIntervalSeconds() {
        return fixedAggregateIntervalSeconds;
    }

    public long getFixedGaugeIntervalSeconds() {
        return fixedGaugeIntervalSeconds;
    }

    @OnlyUsedByTests
    public void close() {
        if (aggregateCollector != null) {
            aggregateCollector.close();
        }
        if (stackTraceCollector != null) {
            stackTraceCollector.close();
        }
    }
}
