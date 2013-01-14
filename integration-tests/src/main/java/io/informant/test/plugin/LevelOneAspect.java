/**
 * Copyright 2011-2013 the original author or authors.
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
import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.Aspect;
import io.informant.api.weaving.InjectThrowable;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class LevelOneAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("io.informant:informant-integration-tests");

    @Pointcut(typeName = "io.informant.test.LevelOne", methodName = "call",
            methodArgs = { "java.lang.String", "java.lang.String" }, metricName = "level one")
    public static class LevelOneAdvice {

        private static final Metric metric = pluginServices.getMetric(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(final String arg1, final String arg2) {
            MessageSupplier messageSupplier = new MessageSupplier() {
                @Override
                public Message get() {
                    String traceHeadline = pluginServices.getStringProperty("alternateHeadline");
                    if (traceHeadline.equals("")) {
                        traceHeadline = "Level One";
                    }
                    if (pluginServices.getBooleanProperty("starredHeadline")) {
                        traceHeadline += "*";
                    }
                    Map<String, ?> detail = ImmutableMap.of("arg1", arg1, "arg2", arg2, "nested1",
                            ImmutableMap.of("nestedkey11", arg1, "nestedkey12", arg2, "subnested1",
                                    ImmutableMap.of("subnestedkey1", arg1, "subnestedkey2", arg2)),
                            "nested2", ImmutableMap.of("nestedkey21", arg1, "nestedkey22", arg2));
                    return Message.withDetail(traceHeadline, detail);
                }
            };
            Span span = pluginServices.startTrace(messageSupplier, metric);
            // several trace attributes to test ordering
            pluginServices.setTraceAttribute(arg1, arg2);
            pluginServices.setTraceAttribute("z", "zz");
            pluginServices.setTraceAttribute("y", "yy");
            pluginServices.setTraceAttribute("x", "xx");
            pluginServices.setTraceAttribute("w", "ww");
            return span;
        }

        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }

        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
            span.end();
        }
    }
}
