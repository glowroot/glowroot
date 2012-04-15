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

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricImpl implements Metric {

    private final String name;
    private final ThreadLocal<TraceMetricImpl> traceMetric = new ThreadLocal<TraceMetricImpl>();

    private final Ticker ticker;

    public MetricImpl(String name, Ticker ticker) {
        this.name = name;
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    TraceMetricImpl start() {
        TraceMetricImpl item = traceMetric.get();
        if (item == null) {
            item = new TraceMetricImpl(name, ticker);
            item.start();
            traceMetric.set(item);
            return item;
        } else {
            item.start();
            return item;
        }
    }

    TraceMetricImpl start(long startTick) {
        TraceMetricImpl item = traceMetric.get();
        if (item == null) {
            item = new TraceMetricImpl(name, ticker);
            item.start(startTick);
            traceMetric.set(item);
            return item;
        } else {
            item.start(startTick);
            return item;
        }
    }

    TraceMetricImpl get() {
        return traceMetric.get();
    }

    void remove() {
        traceMetric.remove();
    }
}
