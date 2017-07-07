/*
 * Copyright 2011-2017 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.jdbc.StatementAspect.HasStatementMirror;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ConnectionAspect {

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty capturePreparedStatementCreation =
            configService.getBooleanProperty("capturePreparedStatementCreation");
    private static final BooleanProperty captureConnectionClose =
            configService.getBooleanProperty("captureConnectionClose");
    private static final BooleanProperty captureConnectionLifecycleTraceEntries =
            configService.getBooleanProperty("captureConnectionLifecycleTraceEntries");
    private static final BooleanProperty captureTransactionLifecycleTraceEntries =
            configService.getBooleanProperty("captureTransactionLifecycleTraceEntries");

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(className = "java.sql.Connection", methodName = "prepare*",
            methodParameterTypes = {"java.lang.String", ".."}, nestingGroup = "jdbc",
            timerName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final TimerName timerName = Agent.getTimerName(PrepareAdvice.class);
        @OnBefore
        public static @Nullable Timer onBefore(ThreadContext context) {
            if (capturePreparedStatementCreation.value()) {
                return context.startTimer(timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasStatementMirror preparedStatement,
                @BindParameter @Nullable String sql) {
            if (preparedStatement == null || sql == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
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
        public static void onReturn(@BindReturn @Nullable HasStatementMirror statement) {
            if (statement == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            statement.glowroot$setStatementMirror(new StatementMirror());
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "commit", methodParameterTypes = {},
            nestingGroup = "jdbc", timerName = "jdbc commit")
    public static class CommitAdvice {
        private static final TimerName timerName = Agent.getTimerName(CommitAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context) {
            return context.startTraceEntry(MessageSupplier.create("jdbc commit"), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "rollback", methodParameterTypes = {},
            nestingGroup = "jdbc", timerName = "jdbc rollback")
    public static class RollbackAdvice {
        private static final TimerName timerName = Agent.getTimerName(RollbackAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context) {
            return context.startTraceEntry(MessageSupplier.create("jdbc rollback"), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "close", methodParameterTypes = {},
            nestingGroup = "jdbc", timerName = "jdbc connection close")
    public static class CloseAdvice {
        private static final TimerName timerName = Agent.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionClose.value() || captureConnectionLifecycleTraceEntries.value();
        }
        @OnBefore
        public static Object onBefore(ThreadContext context) {
            if (captureConnectionLifecycleTraceEntries.value()) {
                return context.startTraceEntry(MessageSupplier.create("jdbc connection close"),
                        timerName);
            } else {
                return context.startTimer(timerName);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler Object entryOrTimer) {
            if (entryOrTimer instanceof TraceEntry) {
                ((TraceEntry) entryOrTimer).endWithLocationStackTrace(
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
            methodParameterTypes = {"boolean"}, nestingGroup = "jdbc",
            timerName = "jdbc set autocommit")
    public static class SetAutoCommitAdvice {
        private static final TimerName timerName = Agent.getTimerName(SetAutoCommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return captureTransactionLifecycleTraceEntries.value();
        }
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindParameter boolean autoCommit) {
            return context.startTraceEntry(
                    MessageSupplier.create("jdbc set autocommit: {}", Boolean.toString(autoCommit)),
                    timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }
}
