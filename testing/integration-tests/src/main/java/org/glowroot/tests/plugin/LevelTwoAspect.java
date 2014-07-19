/*
 * Copyright 2011-2014 the original author or authors.
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

import com.google.common.collect.ImmutableMap;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
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
public class LevelTwoAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.LevelTwo", methodName = "call",
            methodParameterTypes = {"java.lang.String", "java.lang.String"},
            traceMetric = "level two")
    public static class LevelTwoAdvice {

        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LevelTwoAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static Span onBefore(@BindParameter final String arg1,
                @BindParameter final String arg2) {
            return pluginServices.startSpan(new MessageSupplier() {
                @Override
                public Message get() {
                    return Message.withDetail("Level Two",
                            ImmutableMap.of("arg1", arg1, "arg2", arg2));
                }
            }, traceMetricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            span.end();
        }
    }
}
