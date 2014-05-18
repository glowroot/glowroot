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

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Ticker;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class CollectorModule {

    private static final long fixedAggregationIntervalSeconds;

    static {
        fixedAggregationIntervalSeconds =
                Long.getLong("glowroot.internal.collector.aggregationInterval", 300);
    }

    private final TraceCollectorImpl traceCollector;
    @Nullable
    private final TransactionCollector transactionCollector;

    public CollectorModule(Clock clock, Ticker ticker, ConfigModule configModule,
            SnapshotRepository snapshotRepository,
            TransactionPointRepository transactionPointRepository,
            ScheduledExecutorService scheduledExecutor,
            boolean viewerModeEnabled) {
        ConfigService configService = configModule.getConfigService();
        if (viewerModeEnabled) {
            transactionCollector = null;
        } else {
            transactionCollector = new TransactionCollector(scheduledExecutor,
                    transactionPointRepository, clock, fixedAggregationIntervalSeconds);
        }
        // TODO should be no need for trace collector in viewer mode
        traceCollector = new TraceCollectorImpl(scheduledExecutor, configService,
                snapshotRepository, transactionCollector, clock, ticker);
    }

    public TraceCollectorImpl getTraceCollector() {
        return traceCollector;
    }

    public long getFixedAggregationIntervalSeconds() {
        return fixedAggregationIntervalSeconds;
    }

    @OnlyUsedByTests
    public void close() {
        if (transactionCollector != null) {
            transactionCollector.close();
        }
    }
}
