/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.microbenchmarks.support;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

public class TransactionWorthyAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("glowroot-microbenchmarks");

    @Pointcut(className = "org.glowroot.microbenchmarks.core.support.TransactionWorthy",
            methodName = "doSomethingTransactionWorthy", methodParameterTypes = {},
            metricName = "transaction worthy")
    public static class TransactionWorthyAdvice {

        private static final MetricName metricName =
                pluginServices.getMetricName(TransactionWorthyAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }

        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTransaction("Microbenchmark", "transaction worthy",
                    MessageSupplier.from("transaction worthy"), metricName);
        }

        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }
}
