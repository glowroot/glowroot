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

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.Aspect;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class LogCauseExceptionAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("io.informant:informant-integration-tests");

    private static final Exception causeException1 = new IllegalStateException("Cause 1");
    private static final Exception causeException2 = new RuntimeException("Cause 2",
            causeException1);
    private static final Exception causeException3 = new IllegalArgumentException("Cause 3",
            causeException2);

    @Pointcut(typeName = "io.informant.test.LogCauseException", methodName = "log",
            methodArgs = { "java.lang.String" }, metricName = "log error")
    public static class LogCauseExceptionAdvice {

        private static final Metric metric = pluginServices
                .getMetric(LogCauseExceptionAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(String message) {
            return pluginServices.startSpan(MessageSupplier.from("ERROR -- {}", message), metric);

        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.endWithError(ErrorMessage.from(new Exception(causeException3)));
        }
    }
}
