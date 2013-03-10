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
package io.informant.core.trace;

import io.informant.api.Metric;
import io.informant.util.PartiallyThreadSafe;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("get(), getName() can be called from any thread")
public class MetricImpl implements Metric {

    private final String name;
    private final Ticker ticker;

    private final ThreadLocal<TraceMetric> traceMetricHolder = new ThreadLocal<TraceMetric>();

    public MetricImpl(String name, Ticker ticker) {
        this.name = name;
        this.ticker = ticker;
    }

    @Nullable
    public TraceMetric get() {
        return traceMetricHolder.get();
    }

    public TraceMetric create() {
        TraceMetric traceMetric = new TraceMetric(name, ticker);
        traceMetricHolder.set(traceMetric);
        return traceMetric;
    }

    void remove() {
        traceMetricHolder.remove();
    }
}
