/*
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
package io.informant.trace.model;

import com.google.common.base.Ticker;

import io.informant.api.MetricTimer;
import io.informant.markers.Singleton;
import io.informant.markers.ThreadSafe;
import io.informant.weaving.WeavingMetricName;

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
public class WeavingMetricNameImpl implements WeavingMetricName {

    private final MetricNameImpl metricName;

    public WeavingMetricNameImpl(Ticker ticker) {
        metricName = new MetricNameImpl("informant weaving", ticker);
    }

    public MetricTimer start() {
        // create is called at the beginning of every trace, and clear is called at the end of every
        // trace, so metric will be non-null if currently in a trace
        Metric metric = metricName.get();
        if (metric == null) {
            return NopMetricTimer.INSTANCE;
        }
        metric.start();
        return metric;
    }

    Metric create() {
        return metricName.create();
    }

    void clear() {
        metricName.clear();
    }

    @ThreadSafe
    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        public void stop() {}
    }
}
