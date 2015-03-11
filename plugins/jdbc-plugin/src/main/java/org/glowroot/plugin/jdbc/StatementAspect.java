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

import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;

import javax.annotation.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.BooleanProperty;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.NullParameterValue;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;
import org.glowroot.plugin.jdbc.message.BatchPreparedStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.BatchStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.JdbcMessageSupplier;
import org.glowroot.plugin.jdbc.message.PreparedStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.StatementMessageSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// many of the pointcuts are not restricted to pluginServices.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class StatementAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static final BooleanProperty captureBindParameters =
            pluginServices.getEnabledProperty("captureBindParameters");
    private static final BooleanProperty capturePreparedStatementCreation =
            pluginServices.getEnabledProperty("capturePreparedStatementCreation");
    private static final BooleanProperty captureStatementClose =
            pluginServices.getEnabledProperty("captureStatementClose");

    // ===================== Mixin =====================

    @Mixin(target = {"java.sql.Statement", "java.sql.ResultSet"})
    public static class HasStatementMirrorImpl implements HasStatementMirror {
        // the field and method names are verbose to avoid conflict since they will become fields
        // and methods in all classes that extend java.sql.Statement or java.sql.ResultSet
        private volatile @Nullable StatementMirror glowrootStatementMirror;
        @Override
        public @Nullable StatementMirror getGlowrootStatementMirror() {
            return glowrootStatementMirror;
        }
        @Override
        public void setGlowrootStatementMirror(@Nullable StatementMirror glowrootStatementMirror) {
            this.glowrootStatementMirror = glowrootStatementMirror;
        }
        @Override
        public boolean hasGlowrootStatementMirror() {
            return glowrootStatementMirror != null;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.sql.Statement
    public interface HasStatementMirror {
        @Nullable
        StatementMirror getGlowrootStatementMirror();
        void setGlowrootStatementMirror(@Nullable StatementMirror glowrootStatementMirror);
        boolean hasGlowrootStatementMirror();
    }

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(className = "java.sql.Connection", methodName = "prepare*",
            methodParameterTypes = {"java.lang.String", ".."}, ignoreSelfNested = true,
            timerName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(PrepareAdvice.class);
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
            preparedStatement.setGlowrootStatementMirror(new PreparedStatementMirror(sql));
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "createStatement",
            methodParameterTypes = {".."}, ignoreSelfNested = true)
    public static class CreateStatementAdvice {
        @OnReturn
        public static void onReturn(@BindReturn HasStatementMirror statement) {
            // this runs even if glowroot temporarily disabled
            statement.setGlowrootStatementMirror(new StatementMirror());
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(className = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodParameterTypes = {"int", "*", ".."})
    public static class SetXAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement,
                @BindParameter int parameterIndex, @BindParameter @Nullable Object x) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.getGlowrootStatementMirror();
            if (mirror != null) {
                if (x instanceof InputStream || x instanceof Reader) {
                    mirror.setParameterValue(parameterIndex,
                            new StreamingParameterValue(x.getClass()));
                } else if (x instanceof byte[]) {
                    boolean displayAsHex = JdbcPluginProperties.displayBinaryParameterAsHex(
                            mirror.getSql(), parameterIndex);
                    mirror.setParameterValue(parameterIndex,
                            new ByteArrayParameterValue((byte[]) x, displayAsHex));
                } else {
                    mirror.setParameterValue(parameterIndex, x);
                }
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setNull",
            methodParameterTypes = {"int", "int", ".."})
    public static class SetNullAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement,
                @BindParameter int parameterIndex) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.getGlowrootStatementMirror();
            if (mirror != null) {
                mirror.setParameterValue(parameterIndex, new NullParameterValue());
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "clearParameters",
            methodParameterTypes = {})
    public static class ClearParametersAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.getGlowrootStatementMirror();
            if (mirror != null) {
                mirror.clearParameters();
            }
        }
    }

    // ================== Statement Batching ==================

    @Pointcut(className = "java.sql.Statement", methodName = "addBatch",
            methodParameterTypes = {"java.lang.String"}, ignoreSelfNested = true)
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            StatementMirror mirror = statement.getGlowrootStatementMirror();
            if (mirror != null) {
                mirror.addBatch(sql);
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "addBatch",
            methodParameterTypes = {}, ignoreSelfNested = true)
    public static class PreparedStatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.getGlowrootStatementMirror();
            if (mirror != null) {
                mirror.addBatch();
            }
        }
    }

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut(className = "java.sql.Statement", methodName = "clearBatch",
            methodParameterTypes = {})
    public static class ClearBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.getGlowrootStatementMirror();
            if (mirror != null) {
                mirror.clearBatch();
            }
        }
    }

    // =================== Statement Execution ===================

    @Pointcut(className = "java.sql.Statement", methodName = "execute*",
            methodParameterTypes = {"java.lang.String", ".."}, ignoreSelfNested = true,
            timerName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            StatementMirror mirror = statement.getGlowrootStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                return null;
            }
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = new StatementMessageSupplier(sql);
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startTraceEntry(jdbcMessageSupplier, timerName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.clearLastJdbcMessageSupplier();
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn Object returnValue,
                @BindReceiver HasStatementMirror statement,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (returnValue instanceof HasStatementMirror) {
                // Statement can always be retrieved from ResultSet.getStatement(), and
                // StatementMirror from that, but ResultSet.getStatement() is sometimes not super
                // duper fast due to ResultSet wrapping and other checks, so StatementMirror is
                // stored directly in ResultSet as an optimization
                StatementMirror mirror = statement.getGlowrootStatementMirror();
                ((HasStatementMirror) returnValue).setGlowrootStatementMirror(mirror);
            }
            if (traceEntry != null) {
                traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(className = "java.sql.PreparedStatement",
            methodName = "execute|executeQuery|executeUpdate", methodParameterTypes = {},
            ignoreSelfNested = true, timerName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(
                @BindReceiver HasStatementMirror preparedStatement) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.getGlowrootStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                return null;
            }
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier;
                if (captureBindParameters.value()) {
                    jdbcMessageSupplier = new PreparedStatementMessageSupplier(mirror.getSql(),
                            mirror.getParametersCopy());
                } else {
                    jdbcMessageSupplier = new StatementMessageSupplier(mirror.getSql());
                }
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startTraceEntry(jdbcMessageSupplier, timerName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.clearLastJdbcMessageSupplier();
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn Object returnValue,
                @BindReceiver HasStatementMirror preparedStatement,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (returnValue instanceof HasStatementMirror) {
                // PreparedStatement can always be retrieved from ResultSet.getStatement(), and
                // StatementMirror from that, but ResultSet.getStatement() is sometimes not super
                // duper fast due to ResultSet wrapping and other checks, so StatementMirror is
                // stored directly in ResultSet as an optimization
                StatementMirror mirror = preparedStatement.getGlowrootStatementMirror();
                ((HasStatementMirror) returnValue).setGlowrootStatementMirror(mirror);
            }
            if (traceEntry != null) {
                traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeBatch",
            methodParameterTypes = {}, ignoreSelfNested = true, timerName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(StatementExecuteBatchAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver HasStatementMirror statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror =
                        (PreparedStatementMirror) statement.getGlowrootStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                    return null;
                }
                return onBeforePreparedStatement(mirror);
            } else {
                StatementMirror mirror = statement.getGlowrootStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                    return null;
                }
                return onBeforeStatement(mirror);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(ErrorMessage.from(t));
            }
        }
        private static @Nullable TraceEntry onBeforePreparedStatement(
                PreparedStatementMirror mirror) {
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier;
                if (captureBindParameters.value()) {
                    jdbcMessageSupplier = new BatchPreparedStatementMessageSupplier(
                            mirror.getSql(), mirror.getBatchedParametersCopy());
                } else {
                    jdbcMessageSupplier = new StatementMessageSupplier(mirror.getSql());
                }
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startTraceEntry(jdbcMessageSupplier, timerName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                // plugin is re-enabled in the middle of iterating over a different result set
                mirror.clearLastJdbcMessageSupplier();
                return null;
            }
        }
        private static @Nullable TraceEntry onBeforeStatement(StatementMirror mirror) {
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier =
                        new BatchStatementMessageSupplier(mirror.getBatchedSqlCopy());
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startTraceEntry(jdbcMessageSupplier, timerName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                // plugin is re-enabled in the middle of iterating over a different result set
                mirror.clearLastJdbcMessageSupplier();
                return null;
            }
        }
    }

    // ================== Additional ResultSet Tracking ==================

    @Pointcut(className = "java.sql.Statement", methodName = "getResultSet|getGeneratedKeys",
            methodParameterTypes = {".."}, methodReturnType = "java.sql.ResultSet")
    public static class StatementReturnResultSetAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.hasGlowrootStatementMirror();
        }
        @OnReturn
        public static void onReturn(@BindReturn HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.getGlowrootStatementMirror();
            resultSet.setGlowrootStatementMirror(mirror);
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(className = "java.sql.Statement", methodName = "close", methodParameterTypes = {},
            timerName = "jdbc statement close")
    public static class CloseAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.hasGlowrootStatementMirror() && pluginServices.isEnabled();
        }
        @OnBefore
        public static @Nullable Timer onBefore(
                @BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.getGlowrootStatementMirror();
            if (mirror != null) {
                // this should always be true since just checked hasGlowrootStatementMirror() above
                mirror.clearLastJdbcMessageSupplier();
            }
            if (captureStatementClose.value()) {
                return pluginServices.startTimer(timerName);
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }
}
