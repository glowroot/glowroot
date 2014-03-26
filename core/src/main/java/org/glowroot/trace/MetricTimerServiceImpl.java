/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.trace;

import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.model.Trace;
import org.glowroot.weaving.MetricTimerService;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class MetricTimerServiceImpl implements MetricTimerService {

    private final TraceRegistry traceRegistry;

    MetricTimerServiceImpl(TraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    @Override
    public MetricTimer startMetricTimer(MetricName metricName) {
        Trace trace = traceRegistry.getCurrentTrace();
        if (trace == null) {
            return NopMetricTimer.INSTANCE;
        }
        return trace.tryStartMetric(metricName);
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        @Override
        public void stop() {}
    }
}
