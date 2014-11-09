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
package org.glowroot.plugin.jdbc;

import javax.annotation.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DatabaseMetaDataAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    @Pointcut(className = "java.sql.DatabaseMetaData", methodName = "*",
            methodParameterTypes = {".."}, ignoreSelfNested = true, metricName = "jdbc metadata")
    public static class AllMethodAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(AllMethodAdvice.class);
        // plugin configuration property captureDatabaseMetaDataTraceEntries is cached to limit map
        // lookups
        private static volatile boolean pluginEnabled;
        private static volatile boolean entryEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    entryEnabled = pluginEnabled && pluginServices
                            .getBooleanProperty("captureDatabaseMetaDataTraceEntries");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            entryEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureDatabaseMetaDataTraceEntries");
        }
        @OnBefore
        @Nullable
        public static Object onBefore(@BindMethodName String methodName) {
            if (pluginServices.isEnabled()) {
                if (entryEnabled) {
                    return pluginServices.startTraceEntry(MessageSupplier.from("jdbc metadata:"
                            + " DatabaseMetaData.{}()", methodName), metricName);
                } else {
                    return pluginServices.startTransactionMetric(metricName);
                }
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Object entryOrMetric) {
            if (entryOrMetric == null) {
                return;
            }
            if (entryOrMetric instanceof TraceEntry) {
                ((TraceEntry) entryOrMetric).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            } else {
                ((TransactionMetric) entryOrMetric).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Object entryOrMetric) {
            if (entryOrMetric == null) {
                return;
            }
            if (entryOrMetric instanceof TraceEntry) {
                ((TraceEntry) entryOrMetric).endWithError(ErrorMessage.from(t));
            } else {
                ((TransactionMetric) entryOrMetric).stop();
            }
        }
    }
}
