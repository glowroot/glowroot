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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Ticker;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class CollectorModule {

    private static final boolean aggregatesEnabled;
    private static final long fixedAggregateIntervalSeconds;

    static {
        aggregatesEnabled = Boolean.getBoolean("glowroot.experimental.aggregates");
        fixedAggregateIntervalSeconds =
                Long.getLong("glowroot.internal.collector.aggregateInterval", 300);
    }

    @Nullable
    private final Aggregator aggregator;
    private final TraceCollectorImpl traceCollector;

    public CollectorModule(Clock clock, Ticker ticker, ConfigModule configModule,
            SnapshotRepository snapshotRepository, AggregateRepository aggregateRepository,
            ScheduledExecutorService scheduledExecutor) {
        ConfigService configService = configModule.getConfigService();
        if (aggregatesEnabled) {
            aggregator = Aggregator.create(scheduledExecutor, aggregateRepository, clock,
                    fixedAggregateIntervalSeconds);
        } else {
            aggregator = null;
        }
        traceCollector = new TraceCollectorImpl(scheduledExecutor, configService,
                snapshotRepository, aggregator, clock, ticker);
    }

    public TraceCollectorImpl getTraceCollector() {
        return traceCollector;
    }

    public long getFixedAggregateIntervalSeconds() {
        return fixedAggregateIntervalSeconds;
    }

    public boolean getAggregatesEnabled() {
        return aggregatesEnabled;
    }
}
