/*
 * Copyright 2015-2016 the original author or authors.
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
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@VisibleForTesting
public class CollectorProxy implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorProxy.class);

    private volatile @MonotonicNonNull Collector instance;

    @GuardedBy("lock")
    private Map<Long, Aggregates> earlyAggregates = Maps.newLinkedHashMap();

    @GuardedBy("lock")
    private final List<List<GaugeValue>> earlyGaugeValues = Lists.newArrayList();

    @GuardedBy("lock")
    private final List<Trace> earlyTraces = Lists.newArrayList();

    @GuardedBy("lock")
    private final List<LogEvent> earlyLogEvents = Lists.newArrayList();

    private final Object lock = new Object();

    @Override
    public void init(File glowrootBaseDir, Environment environment, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception {
        // init is called directly on the instantiated collector, never on the proxy itself
        throw new UnsupportedOperationException();
    }

    @Override
    public void collectAggregates(long captureTime, Aggregates aggregates) throws Exception {
        synchronized (lock) {
            if (instance == null) {
                if (earlyAggregates.size() < 10) { // 10 minutes
                    earlyAggregates.put(captureTime, aggregates);
                }
                return;
            }
        }
        instance.collectAggregates(captureTime, aggregates);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        synchronized (lock) {
            if (instance == null) {
                if (earlyTraces.size() < 120) { // 10 minutes
                    earlyGaugeValues.add(gaugeValues);
                }
                return;
            }
        }
        instance.collectGaugeValues(gaugeValues);
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        synchronized (lock) {
            if (instance == null) {
                if (earlyTraces.size() < 10) {
                    earlyTraces.add(trace);
                }
                return;
            }
        }
        instance.collectTrace(trace);
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        synchronized (lock) {
            if (instance == null) {
                if (earlyLogEvents.size() < 100) {
                    earlyLogEvents.add(logEvent);
                }
                return;
            }
        }
        instance.log(logEvent);
    }

    @VisibleForTesting
    public void setInstance(Collector instance) {
        synchronized (lock) {
            this.instance = instance;
            try {
                for (Entry<Long, Aggregates> entry : earlyAggregates.entrySet()) {
                    instance.collectAggregates(entry.getKey(), entry.getValue());
                }
                for (List<GaugeValue> gaugeValues : earlyGaugeValues) {
                    instance.collectGaugeValues(gaugeValues);
                }
                for (Trace trace : earlyTraces) {
                    instance.collectTrace(trace);
                }
                for (LogEvent logEvent : earlyLogEvents) {
                    instance.log(logEvent);
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            earlyLogEvents.clear();
        }
    }
}
