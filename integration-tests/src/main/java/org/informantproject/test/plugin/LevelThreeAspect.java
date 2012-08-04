/**
 * Copyright 2011-2012 the original author or authors.
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

import org.informantproject.api.ContextMap;
import org.informantproject.api.Message;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class LevelThreeAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-integration-tests");

    @Pointcut(typeName = "org.informantproject.test.api.LevelThree", methodName = "call",
            methodArgs = { "java.lang.String", "java.lang.String" }, metricName = "level three")
    public static class LevelThreeAdvice {

        private static final Metric metric = pluginServices.getMetric(LevelThreeAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(final String arg1, final String arg2) {
            Supplier<Message> messageSupplier = new Supplier<Message>() {
                @Override
                public Message get() {
                    return Message.withContext("Level Three",
                            ContextMap.of("arg1", arg1, "arg2", arg2));
                }
            };
            return pluginServices.startSpan(messageSupplier, metric);
        }

        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }
}
