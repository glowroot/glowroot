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
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogCauseAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.LogCause", methodName = "log",
            methodParameterTypes = {"java.lang.String"}, metricName = "log error")
    public static class LogCauseAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(LogCauseAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(@BindParameter String message) {
            return pluginServices.startSpan(MessageSupplier.from("ERROR -- {}", message),
                    metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            Exception cause1 = new NullPointerException("Cause 1");
            Exception cause2 = new IllegalStateException("Cause 2", cause1);
            Exception cause3 = new IllegalArgumentException("Cause 3", cause2);
            span.endWithError(ErrorMessage.from(new IllegalStateException(cause3)));
        }
    }
}
