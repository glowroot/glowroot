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
package org.informantproject.test.plugin;

import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.TemplateMessage;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;
import org.informantproject.test.plugin.LogErrorAspect.LogErrorAdvice;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class PauseAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-integration-tests");

    @Pointcut(typeName = "org.informantproject.test.Pause", methodName = "pause",
            methodArgs = { "int" }, metricName = "pause")
    public static class PauseAdvice {

        private static final Metric metric = pluginServices.getMetric(LogErrorAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(int millis) {
            return pluginServices.startSpan(TemplateMessage.of("Pause.pause({{millis}})", millis),
                    metric);
        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    // this is just to generate an additional $informant$ method to test that consecutive
    // $informant$ methods in a span stack trace are stripped out correctly
    @Pointcut(typeName = "org.informantproject.test.LogError", methodName = "pause",
            methodArgs = { "int" }, metricName = "pause 2")
    public static class PauseAdvice2 {}
}
