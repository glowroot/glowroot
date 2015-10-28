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
package org.glowroot.agent.it.harness.impl;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class TraceCollector implements Collector {

    private volatile @Nullable Trace trace;

    public Trace getCompletedTrace(int timeout, TimeUnit unit) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(unit) < timeout) {
            Trace trace = this.trace;
            if (trace != null && !trace.getHeader().getPartial()) {
                return trace;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("No trace was collected");
    }

    public Trace getPartialTrace(int timeout, TimeUnit unit) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(unit) < timeout) {
            Trace trace = this.trace;
            if (trace != null) {
                if (!trace.getHeader().getPartial()) {
                    throw new IllegalStateException("Trace was collected but is not partial");
                }
                return trace;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("No trace was collected");
    }

    public boolean hasTrace() {
        return trace != null;
    }

    public void clearTrace() {
        trace = null;
    }

    @Override
    public void collectAggregates(long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws Exception {}

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {}

    @Override
    public void collectTrace(Trace trace) throws Exception {
        this.trace = trace;
    }
}
