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
package org.glowroot.container;

import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

public class TraceMarkerAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-test-container");

    @Pointcut(className = "org.glowroot.container.TraceMarker", methodName = "traceMarker",
            methodParameterTypes = {}, metricName = "mock trace marker", ignoreSelfNested = true)
    public static class TraceMarkerAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(TraceMarkerAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver Object receiver) {
            String receiverClassName = receiver.getClass().getName();
            return pluginServices.startTransaction("Test harness",
                    "trace marker / " + receiverClassName,
                    MessageSupplier.from("{}.traceMarker()", receiverClassName), metricName);
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
