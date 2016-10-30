/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// DataSource.getConnection() can be interesting in case the data source is improperly sized and is
// slow while expanding
public class DataSourceAspect {

    private static final Logger logger = Agent.getLogger(DataSourceAspect.class);
    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty captureGetConnection =
            configService.getBooleanProperty("captureGetConnection");
    private static final BooleanProperty captureConnectionLifecycleTraceEntries =
            configService.getBooleanProperty("captureConnectionLifecycleTraceEntries");
    private static final BooleanProperty captureTransactionLifecycleTraceEntries =
            configService.getBooleanProperty("captureTransactionLifecycleTraceEntries");

    @Pointcut(className = "javax.sql.DataSource", methodName = "getConnection",
            methodParameterTypes = {".."}, nestingGroup = "jdbc", timer = "jdbc get connection")
    public static class GetConnectionAdvice {
        private static final TimerName timerName = Agent.getTimerName(GetConnectionAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureGetConnection.value() || captureConnectionLifecycleTraceEntries.value();
        }
        @OnBefore
        public static Object onBefore(ThreadContext context) {
            if (captureConnectionLifecycleTraceEntries.value()) {
                return context.startTraceEntry(new GetConnectionMessageSupplier(), timerName);
            } else {
                return context.startTimer(timerName);
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
                ((TraceEntry) entryOrTimer).endWithError(t);
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
                return Message.create("jdbc get connection");
            } else {
                return Message.create("jdbc get connection (autocommit: {})", autoCommit);
            }
        }

        private void setAutoCommit(String autoCommit) {
            this.autoCommit = autoCommit;
        }
    }
}
