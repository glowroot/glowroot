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
package io.informant.test.plugin;

import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;
import io.informant.test.plugin.LogErrorAspect.LogErrorAdvice;

import java.util.concurrent.TimeUnit;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PauseAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant:informant-integration-tests");

    @Pointcut(typeName = "io.informant.test.Pause", methodName = "pause", methodArgs = { "int" },
            metricName = "pause")
    public static class PauseAdvice {

        private static final Metric metric = pluginServices.getMetric(LogErrorAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(int millis) {
            return pluginServices.startSpan(
                    MessageSupplier.from("Pause.pause({})", Integer.toString(millis)), metric);
        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (pluginServices.getBooleanProperty("captureSpanStackTraces")) {
                span.endWithStackTrace(0, TimeUnit.NANOSECONDS);
            } else {
                span.end();
            }
        }
    }

    // this is just to generate an additional $informant$ method to test that consecutive
    // $informant$ methods in a span stack trace are stripped out correctly
    @Pointcut(typeName = "io.informant.test.LogError", methodName = "pause", methodArgs = { "int" },
            metricName = "pause 2")
    public static class PauseAdvice2 {}
}
