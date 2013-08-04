/*
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
package io.informant.tests.plugin;

import java.util.Map;

import io.informant.api.ErrorMessage;
import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.Optional;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;
import io.informant.shaded.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LevelOneAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant:informant-integration-tests");

    @Pointcut(typeName = "io.informant.tests.LevelOne", methodName = "call",
            methodArgs = {"java.lang.String", "java.lang.String"}, metricName = "level one")
    public static class LevelOneAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(@BindMethodArg final String arg1,
                @BindMethodArg final String arg2) {
            String grouping = pluginServices.getStringProperty("alternateGrouping");
            if (grouping.equals("")) {
                grouping = "Level One";
            }
            if (pluginServices.getBooleanProperty("starredGrouping")) {
                grouping += "*";
            }
            final String groupingFinal = grouping;
            MessageSupplier messageSupplier = new MessageSupplier() {
                @Override
                public Message get() {
                    Optional<String> optionalArg2 = Optional.fromNullable(arg2);
                    Map<String, ?> detail = ImmutableMap.of("arg1", arg1, "arg2", optionalArg2,
                            "nested1",
                            ImmutableMap.of("nestedkey11", arg1, "nestedkey12", optionalArg2,
                                    "subnested1", ImmutableMap.of("subnestedkey1", arg1,
                                            "subnestedkey2", optionalArg2)),
                            "nested2", ImmutableMap.of("nestedkey21", arg1,
                                    "nestedkey22", optionalArg2));
                    return Message.withDetail(groupingFinal, detail);
                }
            };
            Span span = pluginServices.startTrace(grouping, messageSupplier, metricName);
            // several trace attributes to test ordering
            pluginServices.setTraceAttribute(arg1, arg2);
            pluginServices.setTraceAttribute("z", "zz");
            pluginServices.setTraceAttribute("y", "yy");
            pluginServices.setTraceAttribute("x", "xx");
            pluginServices.setTraceAttribute("w", "ww");
            return span;
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            Map<String, ?> detail = ImmutableMap.of("ea", "ex", "eb", Optional.absent());
            span.endWithError(ErrorMessage.withDetail(t, detail));
        }

        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }
}
