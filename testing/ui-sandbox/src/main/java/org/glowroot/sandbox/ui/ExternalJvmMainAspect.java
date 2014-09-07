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
package org.glowroot.sandbox.ui;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * This is used to generate a trace with <multiple root nodes> (and with multiple metrics) just to
 * test this unusual situation.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExternalJvmMainAspect {

    private static final PluginServices pluginServices = PluginServices.get("glowroot-ui-sandbox");

    @Pointcut(className = "org.glowroot.container.javaagent.JavaagentContainer",
            methodName = "main", methodParameterTypes = {"java.lang.String[]"},
            metricName = "external jvm main")
    public static class MainAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MainAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTransaction("Sandbox", "javaagent container main",
                    MessageSupplier.from("org.glowroot.container.javaagent.JavaagentContainer"
                            + ".main()"), metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    @Pointcut(className = "org.glowroot.container.javaagent.JavaagentContainer",
            methodName = "metricMarkerOne", metricName = "metric one")
    public static class MetricMarkerOneAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricMarkerOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric transactionMetric) {
            transactionMetric.stop();
        }
    }

    @Pointcut(className = "org.glowroot.container.javaagent.JavaagentContainer",
            methodName = "metricMarkerTwo", metricName = "metric two")
    public static class MetricMarkerTwoAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricMarkerTwoAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric transactionMetric) {
            transactionMetric.stop();
        }
    }
}
