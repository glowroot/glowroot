/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.trace;

import org.informantproject.api.Metric;
import org.informantproject.api.Stopwatch;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricImpl implements Metric {

    private final String name;
    private final ThreadLocal<TraceMetric> traceMetric = new ThreadLocal<TraceMetric>();

    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    public MetricImpl(String name, TraceRegistry traceRegistry, Ticker ticker) {
        this.name = name;
        this.traceRegistry = traceRegistry;
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    public Stopwatch start() {
        Trace currentTrace = traceRegistry.getCurrentTrace();
        if (currentTrace == null) {
            // TODO return global collector?
            return NopStopwatch.INSTANCE;
        } else {
            return currentTrace.startTraceMetric(this);
        }
    }

    TraceMetric startInternal() {
        TraceMetric item = traceMetric.get();
        if (item == null) {
            item = new TraceMetric(name, ticker);
            item.start();
            traceMetric.set(item);
            return item;
        } else {
            item.start();
            return item;
        }
    }

    TraceMetric startInternal(long startTick) {
        TraceMetric item = traceMetric.get();
        if (item == null) {
            item = new TraceMetric(name, ticker);
            item.start(startTick);
            traceMetric.set(item);
            return item;
        } else {
            item.start(startTick);
            return item;
        }
    }

    TraceMetric get() {
        return traceMetric.get();
    }

    void remove() {
        traceMetric.remove();
    }

    private static class NopStopwatch implements Stopwatch {
        private static final NopStopwatch INSTANCE = new NopStopwatch();
        public void stop() {}
    }
}
