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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@VisibleForTesting
public class CollectorProxy implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorProxy.class);

    private volatile @MonotonicNonNull Collector instance;

    private final List<LogEvent> earlyLogEvents = Lists.newCopyOnWriteArrayList();

    @Override
    public void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception {
        if (instance != null) {
            instance.init(glowrootBaseDir, systemInfo, agentConfig, agentConfigUpdater);
        }
    }

    @Override
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception {
        if (instance != null) {
            instance.collectAggregates(captureTime, aggregatesByType);
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        if (instance != null) {
            instance.collectGaugeValues(gaugeValues);
        }
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        if (instance != null) {
            instance.collectTrace(trace);
        }
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        if (instance != null) {
            instance.log(logEvent);
        } else if (earlyLogEvents.size() < 100) {
            earlyLogEvents.add(logEvent);
        }
    }

    @VisibleForTesting
    public void setInstance(Collector instance) {
        this.instance = instance;
        try {
            for (LogEvent logEvent : earlyLogEvents) {
                instance.log(logEvent);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        earlyLogEvents.clear();
    }
}
