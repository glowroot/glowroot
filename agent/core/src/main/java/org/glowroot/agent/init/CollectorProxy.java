/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.concurrent.CountDownLatch;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.collector.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage.LogEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

@VisibleForTesting
public class CollectorProxy implements Collector {

    private volatile @MonotonicNonNull Collector instance;

    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void init(List<File> confDirs, Environment environment, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception {
        // init is called directly on the instantiated collector, never on the proxy itself
        throw new UnsupportedOperationException();
    }

    @Override
    public void collectAggregates(AggregateReader aggregateReader) throws Exception {
        if (instance == null) {
            if (latch.await(2, MINUTES)) {
                checkNotNull(instance).collectAggregates(aggregateReader);
            }
        } else {
            instance.collectAggregates(aggregateReader);
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        if (instance == null) {
            if (latch.await(2, MINUTES)) {
                checkNotNull(instance).collectGaugeValues(gaugeValues);
            }
        } else {
            instance.collectGaugeValues(gaugeValues);
        }
    }

    @Override
    public void collectTrace(TraceReader traceReader) throws Exception {
        if (instance == null) {
            if (latch.await(2, MINUTES)) {
                checkNotNull(instance).collectTrace(traceReader);
            }
        } else {
            instance.collectTrace(traceReader);
        }
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        if (instance == null) {
            if (latch.await(2, MINUTES)) {
                checkNotNull(instance).log(logEvent);
            }
        } else {
            instance.log(logEvent);
        }
    }

    @VisibleForTesting
    public void setInstance(Collector instance) {
        this.instance = instance;
        latch.countDown();
    }
}
