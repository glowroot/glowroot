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

import java.sql.ResultSet;
import java.sql.SQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TransactionMetric;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.StatementAspect.HasStatementMirror;

public class ResultSetAspect {

    private static final Logger logger = LoggerFactory.getLogger(ResultSetAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    @Pointcut(className = "java.sql.ResultSet",
            methodName = "next|previous|relative|absolute|first|last", methodParameterTypes = "..",
            metricName = "jdbc resultset navigate")
    public static class NavigateAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(NavigateAdvice.class);
        private static volatile boolean pluginEnabled;
        // plugin configuration property captureResultSetNavigate is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    metricEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("captureResultSetNavigate");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            metricEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureResultSetNavigate");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return resultSet.hasGlowrootStatementMirror() && pluginEnabled;
        }
        @OnBefore
        @Nullable
        public static TransactionMetric onBefore() {
            if (metricEnabled) {
                return pluginServices.startTransactionMetric(metricName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn boolean currentRowValid,
                @BindReceiver HasStatementMirror resultSet, @BindMethodName String methodName) {
            try {
                StatementMirror mirror = resultSet.getGlowrootStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked above in isEnabled(), unless some
                    // bizarre concurrent mis-usage of ResultSet
                    return;
                }
                RecordCountObject lastRecordCountObject = mirror.getLastRecordCountObject();
                if (lastRecordCountObject == null) {
                    // tracing must be disabled (e.g. exceeded trace entry limit)
                    return;
                }
                if (currentRowValid) {
                    if (methodName.equals("next")) {
                        // ResultSet.getRow() is sometimes not super duper fast due to ResultSet
                        // wrapping and other checks, so this optimizes the common case
                        lastRecordCountObject.incrementNumRows();
                    } else {
                        lastRecordCountObject.updateNumRows(((ResultSet) resultSet).getRow());
                    }
                } else {
                    lastRecordCountObject.setHasPerformedNavigation();
                }
            } catch (SQLException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable TransactionMetric transactionMetric) {
            if (transactionMetric != null) {
                transactionMetric.stop();
            }
        }
    }

    @Pointcut(className = "java.sql.ResultSet", methodName = "get*",
            methodParameterTypes = {"int", ".."}, metricName = "jdbc resultset value")
    public static class ValueAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ValueAdvice.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return metricEnabled && resultSet.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric transactionMetric) {
            transactionMetric.stop();
        }
    }

    @Pointcut(className = "java.sql.ResultSet", methodName = "get*",
            methodParameterTypes = {"java.lang.String", ".."}, metricName = "jdbc resultset value")
    public static class ValueAdvice2 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ValueAdvice2.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return metricEnabled && resultSet.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static TransactionMetric onBefore() {
            return pluginServices.startTransactionMetric(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TransactionMetric transactionMetric) {
            transactionMetric.stop();
        }
    }
}
