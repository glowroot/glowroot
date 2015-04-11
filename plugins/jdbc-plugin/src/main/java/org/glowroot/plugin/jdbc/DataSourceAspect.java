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
package org.glowroot.plugin.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.BooleanProperty;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
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

    private static final BooleanProperty captureConnectionLifecycleTraceEntries =
            pluginServices.getEnabledProperty("captureConnectionLifecycleTraceEntries");
    private static final BooleanProperty captureTransactionLifecycleTraceEntries =
            pluginServices.getEnabledProperty("captureTransactionLifecycleTraceEntries");

    @Pointcut(className = "javax.sql.DataSource", methodName = "getConnection",
            methodParameterTypes = {".."}, timerName = "jdbc get connection")
    public static class GetConnectionAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(GetConnectionAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleTraceEntries.value()) {
                return pluginServices.startTraceEntry(new GetConnectionMessageSupplier(),
                        timerName);
            } else {
                return pluginServices.startTimer(timerName);
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn Connection connection,
                @BindTraveler Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                onReturnTraceEntry(connection, entryOrTimer);
            } else {
                ((Timer) entryOrTimer).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                ((TraceEntry) entryOrTimer).endWithError(ErrorMessage.from(t));
            } else {
                ((Timer) entryOrTimer).stop();
            }
        }
        // split out to separate method so it doesn't affect inlining budget of common case
        private static void onReturnTraceEntry(Connection connection, Object entryOrTimer) {
            TraceEntry traceEntry = (TraceEntry) entryOrTimer;
            if (captureTransactionLifecycleTraceEntries.value()) {
                GetConnectionMessageSupplier messageSupplier =
                        (GetConnectionMessageSupplier) traceEntry.getMessageSupplier();
                if (messageSupplier != null) {
                    // messageSupplier can be null if max trace entries was exceeded
                    String autoCommit;
                    try {
                        autoCommit = Boolean.toString(connection.getAutoCommit());
                    } catch (SQLException e) {
                        logger.warn(e.getMessage(), e);
                        // using toString() instead of getMessage() in order to capture exception
                        // class name
                        autoCommit = "<error occurred: " + e.toString() + ">";
                    }
                    messageSupplier.setAutoCommit(autoCommit);
                }
            }
            traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
    }

    private static class GetConnectionMessageSupplier extends MessageSupplier {

        private volatile @MonotonicNonNull String autoCommit;

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
