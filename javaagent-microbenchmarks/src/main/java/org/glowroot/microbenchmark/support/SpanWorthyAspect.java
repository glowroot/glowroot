/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.microbenchmark.support;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpanWorthyAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmark");

    @Pointcut(type = "org.glowroot.microbenchmark.support.SpanWorthy",
            methodName = "doSomethingSpanWorthy", methodArgTypes = {}, traceMetric = "span worthy")
    public static class SpanWorthyAdvice {

        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(SpanWorthyAdvice.class);

        @OnBefore
        public static Span onBefore() {
            return pluginServices.startSpan(MessageSupplier.from("span worthy"), traceMetricName);
        }

        @OnAfter
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }
}
