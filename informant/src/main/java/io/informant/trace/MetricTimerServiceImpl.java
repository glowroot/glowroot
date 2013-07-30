/*
 * Copyright 2013 the original author or authors.
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
package io.informant.trace;

import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.markers.ThreadSafe;
import io.informant.trace.model.Metric;
import io.informant.trace.model.MetricNameImpl;
import io.informant.trace.model.Trace;
import io.informant.weaving.MetricTimerService;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class MetricTimerServiceImpl implements MetricTimerService {

    private final MetricNameCache metricNameCache;
    private final TraceRegistry traceRegistry;

    MetricTimerServiceImpl(MetricNameCache metricNameCache, TraceRegistry traceRegistry) {
        this.metricNameCache = metricNameCache;
        this.traceRegistry = traceRegistry;
    }

    public MetricName getMetricName(String name) {
        return metricNameCache.getMetricName(name);
    }

    public MetricTimer startMetricTimer(MetricName metricName) {
        // don't call MetricImpl.start() in case this method returns NopTimer.INSTANCE below
        Metric metric = ((MetricNameImpl) metricName).get();
        if (metric == null) {
            // don't access trace thread local unless necessary
            Trace trace = traceRegistry.getCurrentTrace();
            if (trace == null) {
                return NopMetricTimer.INSTANCE;
            }
            metric = trace.addMetric((MetricNameImpl) metricName);
        }
        metric.start();
        return metric;
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        public void stop() {}
    }
}
