/*
 * Copyright 2015 the original author or authors.
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

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;
import org.glowroot.wire.api.model.LogEventOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@VisibleForTesting
public class CollectorProxy implements Collector {

    private volatile @MonotonicNonNull Collector instance;

    @Override
    public void collectJvmInfo(JvmInfo jvmInfo) throws Exception {
        if (instance != null) {
            instance.collectJvmInfo(jvmInfo);
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
        }
    }

    @Override
    public void close() throws InterruptedException {
        if (instance != null) {
            instance.close();
        }
    }

    @VisibleForTesting
    public void setInstance(Collector instance) {
        this.instance = instance;
    }
}
