/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.collector;

import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;

import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class CollectorModule {

    private static final long fixedAggregateIntervalSeconds;

    static {
        fixedAggregateIntervalSeconds =
                Long.getLong("informant.internal.collector.aggregateInterval", 300);
    }

    private final Aggregator aggregator;
    private final TraceCollectorImpl traceCollector;

    public CollectorModule(Clock clock, Ticker ticker, ConfigModule configModule,
            SnapshotRepository snapshotRepository, AggregateRepository aggregateRepository,
            ScheduledExecutorService scheduledExecutor) {
        ConfigService configService = configModule.getConfigService();
        aggregator = new Aggregator(scheduledExecutor, aggregateRepository, clock,
                fixedAggregateIntervalSeconds);
        traceCollector = new TraceCollectorImpl(scheduledExecutor, configService,
                snapshotRepository, aggregator, clock, ticker);
    }

    public Aggregator getAggregator() {
        return aggregator;
    }

    public TraceCollectorImpl getTraceCollector() {
        return traceCollector;
    }

    public long getFixedAggregateIntervalSeconds() {
        return fixedAggregateIntervalSeconds;
    }
}
