/*
 * Copyright 2012-2014 the original author or authors.
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

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

public class LogErrorAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.LogError", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, metricName = "log error")
    public static class LogErrorAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(LogErrorAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindParameter String message) {
            return pluginServices.startTraceEntry(MessageSupplier.from("ERROR -- {}", message),
                    metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from("test error message")).captureStackTrace();
        }
    }

    @Pointcut(className = "org.glowroot.tests.LogError", methodName = "addNestedErrorEntry",
            metricName = "add nested error entry")
    public static class AddErrorEntryAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(AddErrorEntryAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            TraceEntry traceEntry = pluginServices.startTraceEntry(
                    MessageSupplier.from("outer entry to test nesting level"),
                    metricName);
            pluginServices.addTraceEntry(ErrorMessage.from("test add nested error entry message"));
            return traceEntry;
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }

    // this is just to generate an additional $glowroot$ method to test that consecutive
    // $glowroot$ methods in an entry stack trace are stripped out correctly
    @Pointcut(className = "org.glowroot.tests.LogError", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, metricName = "log error 2")
    public static class LogErrorAdvice2 {}
}
