/**
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
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.InjectTarget;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExternalJvmMainAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant:informant-ui-testing");

    @Pointcut(typeName = "io.informant.testkit.ExternalJvmExecutionAdapter", methodName = "main",
            methodArgs = { "java.lang.String[]" }, metricName = "external jvm main")
    public static class MainAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(MainAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(@InjectTarget Class<?> target) {
            return pluginServices.startTrace(
                    MessageSupplier.from("ExternalJvmExecutionAdapter.main()", target.getName()),
                    metricName);
        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }
}
