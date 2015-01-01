/*
 * Copyright 2011-2015 the original author or authors.
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

import java.util.Map.Entry;

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
import org.glowroot.tests.TraceGenerator;

public class TraceGeneratorAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-integration-tests");

    @Pointcut(className = "org.glowroot.tests.TraceGenerator", methodName = "call",
            methodParameterTypes = {"boolean"}, metricName = "trace generator")
    public static class LevelOneAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(LevelOneAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore(@BindReceiver TraceGenerator traceGenerator) {
            TraceEntry traceEntry = pluginServices.startTransaction(
                    traceGenerator.transactionType(), traceGenerator.transactionName(),
                    MessageSupplier.from(traceGenerator.headline()), metricName);
            for (Entry<String, String> entry : traceGenerator.customAttributes().entrySet()) {
                pluginServices.setTransactionCustomAttribute(entry.getKey(), entry.getValue());
            }
            if (traceGenerator.error() != null) {
                pluginServices.setTransactionError(traceGenerator.error());
            }
            return traceEntry;
        }

        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
    }
}
