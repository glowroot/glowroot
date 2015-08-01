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

import javax.annotation.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.BooleanProperty;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.StatementAspect.HasStatementMirror;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ConnectionAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static final BooleanProperty capturePreparedStatementCreation =
            pluginServices.getEnabledProperty("capturePreparedStatementCreation");
    private static final BooleanProperty captureConnectionClose =
            pluginServices.getEnabledProperty("captureConnectionClose");
    private static final BooleanProperty captureConnectionLifecycleTraceEntries =
            pluginServices.getEnabledProperty("captureConnectionLifecycleTraceEntries");
    private static final BooleanProperty captureTransactionLifecycleTraceEntries =
            pluginServices.getEnabledProperty("captureTransactionLifecycleTraceEntries");

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(className = "java.sql.Connection", methodName = "prepare*",
            methodParameterTypes = {"java.lang.String", ".."}, timerName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final TimerName timerName = pluginServices.getTimerName(PrepareAdvice.class);
        @OnBefore
        public static @Nullable Timer onBefore() {
            if (capturePreparedStatementCreation.value()) {
                return pluginServices.startTimer(timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn HasStatementMirror preparedStatement,
                @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            // this runs even if plugin is temporarily disabled
            preparedStatement.glowroot$setStatementMirror(new PreparedStatementMirror(sql));
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "createStatement",
            methodParameterTypes = {".."})
    public static class CreateStatementAdvice {
        @OnReturn
        public static void onReturn(@BindReturn HasStatementMirror statement) {
            // this runs even if glowroot temporarily disabled
            statement.glowroot$setStatementMirror(new StatementMirror());
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "commit", methodParameterTypes = {},
            timerName = "jdbc commit", ignoreSelfNested = true)
    public static class CommitAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTraceEntry(MessageSupplier.from("jdbc commit"), timerName);
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

    @Pointcut(className = "java.sql.Connection", methodName = "rollback", methodParameterTypes = {},
            timerName = "jdbc rollback", ignoreSelfNested = true)
    public static class RollbackAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(RollbackAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return pluginServices.startTraceEntry(MessageSupplier.from("jdbc rollback"), timerName);
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

    @Pointcut(className = "java.sql.Connection", methodName = "close", methodParameterTypes = {},
            timerName = "jdbc connection close", ignoreSelfNested = true)
    public static class CloseAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && (captureConnectionClose.value()
                    || captureConnectionLifecycleTraceEntries.value());
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleTraceEntries.value()) {
                return pluginServices.startTraceEntry(
                        MessageSupplier.from("jdbc connection close"),
                        timerName);
            } else {
                return pluginServices.startTimer(timerName);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                ((TraceEntry) entryOrTimer).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
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
    }

    @Pointcut(className = "java.sql.Connection", methodName = "setAutoCommit",
            methodParameterTypes = {"boolean"}, timerName = "jdbc set autocommit",
            ignoreSelfNested = true)
    public static class SetAutoCommitAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureTransactionLifecycleTraceEntries.value();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter boolean autoCommit) {
            return pluginServices.startTraceEntry(
                    MessageSupplier.from("jdbc set autocommit: {}", Boolean.toString(autoCommit)),
                    timerName);
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
