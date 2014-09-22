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

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConnectionAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static volatile boolean captureConnectionLifecycleTraceEntries;
    private static volatile boolean captureTransactionLifecycleTraceEntries;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureConnectionLifecycleTraceEntries = pluginServices.isEnabled()
                        && pluginServices.getBooleanProperty(
                                "captureConnectionLifecycleTraceEntries");
                captureTransactionLifecycleTraceEntries = pluginServices.isEnabled()
                        && pluginServices.getBooleanProperty(
                                "captureTransactionLifecycleTraceEntries");
            }
        });
        captureConnectionLifecycleTraceEntries = pluginServices.isEnabled()
                && pluginServices.getBooleanProperty(
                        "captureConnectionLifecycleTraceEntries");
        captureTransactionLifecycleTraceEntries = pluginServices.isEnabled()
                && pluginServices.getBooleanProperty(
                        "captureTransactionLifecycleTraceEntries");
    }

    @Pointcut(className = "java.sql.Connection", methodName = "commit", ignoreSelfNested = true,
            metricName = "jdbc commit")
    public static class CommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTraceEntry(MessageSupplier.from("jdbc commit"), metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "rollback", ignoreSelfNested = true,
            metricName = "jdbc rollback")
    public static class RollbackAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(RollbackAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices
                    .startTraceEntry(MessageSupplier.from("jdbc rollback"), metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "close", ignoreSelfNested = true,
            metricName = "jdbc connection close")
    public static class CloseAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleTraceEntries) {
                return pluginServices.startTraceEntry(
                        MessageSupplier.from("jdbc connection close"),
                        metricName);
            } else {
                return pluginServices.startTransactionMetric(metricName);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler Object entryOrMetric) {
            if (entryOrMetric instanceof TraceEntry) {
                ((TraceEntry) entryOrMetric).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            } else {
                ((TransactionMetric) entryOrMetric).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Object entryOrMetric) {
            if (entryOrMetric instanceof TraceEntry) {
                ((TraceEntry) entryOrMetric).endWithError(ErrorMessage.from(t));
            } else {
                ((TransactionMetric) entryOrMetric).stop();
            }
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "setAutoCommit",
            ignoreSelfNested = true, methodParameterTypes = {"boolean"},
            metricName = "jdbc set autocommit")
    public static class SetAutoCommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureTransactionLifecycleTraceEntries;
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter boolean autoCommit) {
            return pluginServices.startTraceEntry(
                    MessageSupplier.from("jdbc set autocommit: {}", Boolean.toString(autoCommit)),
                    metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }
}
