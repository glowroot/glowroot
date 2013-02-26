/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.core;

import io.informant.api.Metric;
import io.informant.util.PartiallyThreadSafe;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("get(), getName() can be called from any thread")
class MetricImpl implements Metric {

    private final String name;
    private final ThreadLocal<TraceMetric> traceMetricHolder = new ThreadLocal<TraceMetric>() {
        @Override
        protected TraceMetric initialValue() {
            return new TraceMetric(name, ticker);
        }
    };

    private final Ticker ticker;

    MetricImpl(String name, Ticker ticker) {
        this.name = name;
        this.ticker = ticker;
    }

    TraceMetric get() {
        return traceMetricHolder.get();
    }

    TraceMetric start(long startTick) {
        TraceMetric traceMetric = traceMetricHolder.get();
        traceMetric.start(startTick);
        return traceMetric;
    }

    void resetTraceMetric() {
        traceMetricHolder.get().reset();
    }
}
