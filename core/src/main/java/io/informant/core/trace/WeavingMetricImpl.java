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

import io.informant.api.MetricTimer;
import io.informant.core.util.ThreadSafe;
import io.informant.core.weaving.WeavingMetric;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Weaving metric is a very special case because it is measuring timing inside of
 * ClassFileTransformation.transform(), and so it should not load/depend on lots of other classes.
 * (otherwise the weaving API could perform metric collection via the normal
 * PluginServices.startTimer())
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class WeavingMetricImpl implements WeavingMetric {

    private final MetricImpl metricImpl;

    @Inject
    WeavingMetricImpl(Ticker ticker) {
        metricImpl = new MetricImpl("informant weaving", ticker);
    }

    public MetricTimer start() {
        // initTraceMetric is called at the beginning of every trace, and resetTraceMetric is called
        // at the end of every trace, so isLinkedToTrace() can be used to check if currently in a
        // trace
        TraceMetric traceMetric = metricImpl.get();
        if (!traceMetric.isLinkedToTrace()) {
            return NopMetricTimer.INSTANCE;
        }
        traceMetric.start();
        return traceMetric;
    }

    TraceMetric initTraceMetric() {
        TraceMetric traceMetric = metricImpl.get();
        traceMetric.setLinkedToTrace();
        return traceMetric;
    }

    void resetTraceMetric() {
        metricImpl.resetTraceMetric();
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        public void stop() {}
    }
}
