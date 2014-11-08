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

import java.sql.Connection;
import java.sql.SQLException;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// DataSource.getConnection() can be interesting in case the data source is improperly sized and is
// slow while expanding
public class DataSourceAspect {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static volatile boolean captureConnectionLifecycleTraceEntries;
    private static volatile boolean captureTransactionLifecycleTraceEntries;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureConnectionLifecycleTraceEntries =
                        pluginServices.getBooleanProperty("captureConnectionLifecycleTraceEntries");
                captureTransactionLifecycleTraceEntries = pluginServices
                        .getBooleanProperty("captureTransactionLifecycleTraceEntries");
            }
        });
        captureConnectionLifecycleTraceEntries =
                pluginServices.getBooleanProperty("captureConnectionLifecycleTraceEntries");
        captureTransactionLifecycleTraceEntries =
                pluginServices.getBooleanProperty("captureTransactionLifecycleTraceEntries");
    }

    @Pointcut(className = "javax.sql.DataSource", methodName = "getConnection",
            methodParameterTypes = {".."}, ignoreSelfNested = true,
            metricName = "jdbc get connection")
    public static class CommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleTraceEntries) {
                return pluginServices.startTraceEntry(new GetConnectionMessageSupplier(),
                        metricName);
            } else {
                return pluginServices.startTransactionMetric(metricName);
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn Connection connection,
                @BindTraveler Object entryOrMetric) {
            if (entryOrMetric instanceof TraceEntry) {
                TraceEntry traceEntry = (TraceEntry) entryOrMetric;
                if (captureTransactionLifecycleTraceEntries) {
                    String autoCommit;
                    try {
                        autoCommit = Boolean.toString(connection.getAutoCommit());
                    } catch (SQLException e) {
                        logger.warn(e.getMessage(), e);
                        autoCommit = "unknown";
                    }
                    GetConnectionMessageSupplier messageSupplier =
                            (GetConnectionMessageSupplier) traceEntry.getMessageSupplier();
                    if (messageSupplier != null) {
                        // messageSupplier can be null if NopTraceEntry
                        messageSupplier.setAutoCommit(autoCommit);
                    }
                }
                traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
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

    private static class GetConnectionMessageSupplier extends MessageSupplier {

        @MonotonicNonNull
        private volatile String autoCommit;

        @Override
        public Message get() {
            if (autoCommit == null) {
                return Message.from("jdbc get connection");
            } else {
                return Message.from("jdbc get connection (autocommit: {})", autoCommit);
            }
        }

        private void setAutoCommit(String autoCommit) {
            this.autoCommit = autoCommit;
        }
    }
}
