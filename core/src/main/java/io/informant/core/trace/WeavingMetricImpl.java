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
package io.informant.core.trace;

import io.informant.api.Timer;
import io.informant.core.weaving.WeavingMetric;

import javax.annotation.concurrent.ThreadSafe;

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
    public WeavingMetricImpl(Ticker ticker) {
        metricImpl = new MetricImpl("informant weaving", ticker);
    }

    public Timer start() {
        // initThreadLocal is called at the beginning of every trace, and clearThreadLocal is called
        // at the end of every trace, so metricImpl.get() can be used to check if currently in a
        // trace
        if (metricImpl.get() == null) {
            return NopTimer.INSTANCE;
        } else {
            return metricImpl.start();
        }
    }

    public TraceMetric initThreadLocal() {
        return metricImpl.initThreadLocal();
    }

    public void clearThreadLocal() {
        metricImpl.clearThreadLocal();
    }

    @ThreadSafe
    private static class NopTimer implements Timer {
        private static final NopTimer INSTANCE = new NopTimer();
        public void end() {}
    }
}
