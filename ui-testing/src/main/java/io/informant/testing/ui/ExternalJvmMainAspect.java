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
package io.informant.testing.ui;

import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;

/**
 * This is used to generate a trace with <multiple root nodes> (and with multiple metrics) just to
 * test this unusual situation.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExternalJvmMainAspect {

    private static final PluginServices pluginServices = PluginServices.get("informant-ui-testing");

    @Pointcut(typeName = "io.informant.container.javaagent.JavaagentContainer",
            methodName = "main", methodArgs = {"java.lang.String[]"},
            metricName = "external jvm main")
    public static class MainAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MainAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(@BindTarget Class<?> target) {
            return pluginServices.startTrace("javaagent container main",
                    MessageSupplier.from("io.informant.container.javaagent.JavaagentContainer"
                            + ".main()", target.getName()), metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "io.informant.container.javaagent.JavaagentContainer",
            methodName = "metricOne", metricName = "metric one")
    public static class MetricOneAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    @Pointcut(typeName = "io.informant.container.javaagent.JavaagentContainer",
            methodName = "metricTwo", metricName = "metric two")
    public static class MetricTwoAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MetricTwoAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }
}
