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
package org.glowroot.microbenchmarks.core.support;

import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

public class MetricWorthyAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");

    @Pointcut(className = "org.glowroot.microbenchmarks.core.support.MetricWorthy",
            methodName = "doSomethingMetricWorthy", methodParameterTypes = {},
            metricName = "metric worthy")
    public static class MetricWorthyAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricWorthyAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "org.glowroot.microbenchmarks.core.support.MetricWorthy",
            methodName = "doSomethingMetricWorthyB", methodParameterTypes = {},
            metricName = "metric worthy B")
    public static class MetricWorthyAdviceB {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricWorthyAdviceB.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric timer) {
            timer.stop();
        }
    }
}
