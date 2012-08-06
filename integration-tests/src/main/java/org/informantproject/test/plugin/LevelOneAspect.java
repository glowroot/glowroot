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

import java.util.Map;

import org.informantproject.api.Message;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.TemplateMessage;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectThrowable;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.OnThrow;
import org.informantproject.api.weaving.Pointcut;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class LevelOneAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-integration-tests");

    @Pointcut(typeName = "org.informantproject.test.api.LevelOne", methodName = "call",
            methodArgs = { "java.lang.String", "java.lang.String" }, metricName = "level one")
    public static class LevelOneAdvice {

        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(final String arg1, final String arg2) {
            Supplier<Message> messageSupplier = new Supplier<Message>() {
                public Message get() {
                    String traceDescription = Objects.firstNonNull(
                            pluginServices.getStringProperty("alternateDescription"), "Level One");
                    if (pluginServices.getBooleanProperty("starredDescription")) {
                        traceDescription += "*";
                    }
                    Map<String, ?> contextMap = ImmutableMap.of("arg1", arg1, "arg2", arg2,
                            "nested1", ImmutableMap.of("nestedkey11", arg1, "nestedkey12", arg2,
                                    "subnested1",
                                    ImmutableMap.of("subnestedkey1", arg1, "subnestedkey2", arg2)),
                            "nested2", ImmutableMap.of("nestedkey21", arg1, "nestedkey22", arg2));
                    return TemplateMessage.of(traceDescription, contextMap);
                }
            };
            return pluginServices.startTrace(messageSupplier, metric);
        }

        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(t);
        }

        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
            span.end();
        }
    }
}
