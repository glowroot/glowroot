/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.microbenchmark.support;

import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.TraceMetricTimer;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceMetricWorthyAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmark");

    @Pointcut(type = "org.glowroot.microbenchmark.support.TraceMetricWorthy",
            methodName = "doSomethingTraceMetricWorthy", methodArgTypes = {},
            traceMetric = "trace metric worthy")
    public static class TraceMetricWorthyAdvice {

        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(TraceMetricWorthyAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return false;
        }

        @OnBefore
        public static TraceMetricTimer onBefore() {
            return pluginServices.startTraceMetric(traceMetricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceMetricTimer timer) {
            timer.stop();
        }
    }
}
