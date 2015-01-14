/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.tests.plugin;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.tests.plugin.LogErrorAspect.LogErrorAdvice;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class PauseAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.Pause", methodName = "pause*",
            methodParameterTypes = {}, metricName = "pause")
    public static class PauseAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(LogErrorAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTraceEntry(
                    MessageSupplier.from("Pause.pauseOneMillisecond()"), metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            if (pluginServices.getBooleanProperty("captureTraceEntryStackTraces")) {
                traceEntry.endWithStackTrace(0, NANOSECONDS);
            } else {
                traceEntry.end();
            }
        }
    }

    // this is just to generate an additional $glowroot$ method to test that consecutive
    // $glowroot$ methods in an entry stack trace are stripped out correctly
    @Pointcut(className = "org.glowroot.tests.LogError", methodName = "pause",
            methodParameterTypes = {"int"}, metricName = "pause 2")
    public static class PauseAdvice2 {}
}
