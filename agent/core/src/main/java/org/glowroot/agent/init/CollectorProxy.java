/*
 * Copyright 2015-2017 the original author or authors.
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

import java.io.File;
import java.util.List;
import java.util.Queue;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Queues;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;

@VisibleForTesting
public class CollectorProxy implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorProxy.class);

    private volatile @MonotonicNonNull Collector instance;

    // 10 minutes of aggregates
    private final Queue<AggregateReader> earlyAggregateReaders = Queues.newArrayBlockingQueue(10);

    // 10 minutes of gauge values
    private final Queue<List<GaugeValue>> earlyGaugeValues = Queues.newArrayBlockingQueue(120);

    private final Queue<TraceReader> earlyTraceReaders = Queues.newArrayBlockingQueue(10);

    private final Queue<LogEvent> earlyLogEvents = Queues.newArrayBlockingQueue(100);

    @Override
    public void init(File confDir, @Nullable File sharedConfDir, Environment environment,
            AgentConfig agentConfig, AgentConfigUpdater agentConfigUpdater) throws Exception {
        // init is called directly on the instantiated collector, never on the proxy itself
        throw new UnsupportedOperationException();
    }

    @Override
    public void collectAggregates(AggregateReader aggregateReader) throws Exception {
        if (instance == null) {
            earlyAggregateReaders.offer(aggregateReader);
            if (instance != null) {
                // just in case the instance field was set and the final drain occurred in between
                // the conditional check and the offer above
                earlyAggregateReaders.remove(aggregateReader);
                instance.collectAggregates(aggregateReader);
            }
        } else {
            instance.collectAggregates(aggregateReader);
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        if (instance == null) {
            earlyGaugeValues.offer(gaugeValues);
            if (instance != null) {
                // just in case the instance field was set and the final drain occurred in between
                // the conditional check and the offer above
                earlyGaugeValues.remove(gaugeValues);
                instance.collectGaugeValues(gaugeValues);
            }
        } else {
            instance.collectGaugeValues(gaugeValues);
        }
    }

    @Override
    public void collectTrace(TraceReader traceReader) throws Exception {
        if (instance == null) {
            earlyTraceReaders.offer(traceReader);
            if (instance != null) {
                // just in case the instance field was set and the final drain occurred in between
                // the conditional check and the offer above
                earlyTraceReaders.remove(traceReader);
                instance.collectTrace(traceReader);
            }
        } else {
            instance.collectTrace(traceReader);
        }
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        if (instance == null) {
            earlyLogEvents.offer(logEvent);
            if (instance != null) {
                // just in case the instance field was set and the final drain occurred in between
                // the conditional check and the offer above
                earlyLogEvents.remove(logEvent);
                instance.log(logEvent);
            }
        } else {
            instance.log(logEvent);
        }
    }

    @VisibleForTesting
    public void setInstance(Collector instance) {
        drainTo(instance);
        // drain a second time to help preserve order and not encounter anything in the final drain
        // (which at worst could lead to unordered log event delivery)
        drainTo(instance);
        this.instance = instance;
        // need to drain one last time in case anything was added in between second drain and
        // setting the instance field
        drainTo(instance);
    }

    private void drainTo(Collector instance) {
        try {
            while (!earlyAggregateReaders.isEmpty()) {
                instance.collectAggregates(earlyAggregateReaders.remove());
            }
            while (!earlyGaugeValues.isEmpty()) {
                instance.collectGaugeValues(earlyGaugeValues.remove());
            }
            while (!earlyTraceReaders.isEmpty()) {
                instance.collectTrace(earlyTraceReaders.remove());
            }
            while (!earlyLogEvents.isEmpty()) {
                instance.log(earlyLogEvents.remove());
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }
}
