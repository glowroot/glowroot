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

import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.config.BooleanProperty;
import org.glowroot.plugin.api.config.ConfigService;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.BindParameter;
import org.glowroot.plugin.api.weaving.BindReturn;
import org.glowroot.plugin.api.weaving.BindThrowable;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.OnReturn;
import org.glowroot.plugin.api.weaving.OnThrow;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.StatementAspect.HasStatementMirror;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ConnectionAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty capturePreparedStatementCreation =
            configService.getEnabledProperty("capturePreparedStatementCreation");
    private static final BooleanProperty captureConnectionClose =
            configService.getEnabledProperty("captureConnectionClose");
    private static final BooleanProperty captureConnectionLifecycleTraceEntries =
            configService.getEnabledProperty("captureConnectionLifecycleTraceEntries");
    private static final BooleanProperty captureTransactionLifecycleTraceEntries =
            configService.getEnabledProperty("captureTransactionLifecycleTraceEntries");

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(className = "java.sql.Connection", methodName = "prepare*",
            methodParameterTypes = {"java.lang.String", ".."}, timerName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(PrepareAdvice.class);
        @OnBefore
        public static @Nullable Timer onBefore() {
            if (capturePreparedStatementCreation.value()) {
                return transactionService.startTimer(timerName);
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
                transactionService.getTimerName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return transactionService.startTraceEntry(MessageSupplier.from("jdbc commit"),
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
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "rollback", methodParameterTypes = {},
            timerName = "jdbc rollback", ignoreSelfNested = true)
    public static class RollbackAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(RollbackAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore() {
            return transactionService.startTraceEntry(MessageSupplier.from("jdbc rollback"),
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
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "close", methodParameterTypes = {},
            timerName = "jdbc connection close", ignoreSelfNested = true)
    public static class CloseAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return configService.isEnabled() && (captureConnectionClose.value()
                    || captureConnectionLifecycleTraceEntries.value());
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleTraceEntries.value()) {
                return transactionService
                        .startTraceEntry(MessageSupplier.from("jdbc connection close"), timerName);
            } else {
                return transactionService.startTimer(timerName);
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
                ((TraceEntry) entryOrTimer).endWithError(t);
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
                transactionService.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureTransactionLifecycleTraceEntries.value();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter boolean autoCommit) {
            return transactionService.startTraceEntry(
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
            traceEntry.endWithError(t);
        }
    }
}
