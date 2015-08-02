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

import java.sql.PreparedStatement;

import javax.annotation.Nullable;

import org.glowroot.plugin.api.ErrorMessage;
import org.glowroot.plugin.api.MessageSupplier;
import org.glowroot.plugin.api.PluginServices;
import org.glowroot.plugin.api.PluginServices.BooleanProperty;
import org.glowroot.plugin.api.QueryEntry;
import org.glowroot.plugin.api.Timer;
import org.glowroot.plugin.api.TimerName;
import org.glowroot.plugin.api.weaving.BindParameter;
import org.glowroot.plugin.api.weaving.BindReceiver;
import org.glowroot.plugin.api.weaving.BindReturn;
import org.glowroot.plugin.api.weaving.BindThrowable;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.Mixin;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.OnReturn;
import org.glowroot.plugin.api.weaving.OnThrow;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.glowroot.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;
import org.glowroot.plugin.jdbc.message.BatchPreparedStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.BatchPreparedStatementMessageSupplier2;
import org.glowroot.plugin.jdbc.message.BatchStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.PreparedStatementMessageSupplier;
import org.glowroot.plugin.jdbc.message.StatementMessageSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// many of the pointcuts are not restricted to pluginServices.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class StatementAspect {

    private static final String QUERY_TYPE = "SQL";

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static final BooleanProperty captureBindParameters =
            pluginServices.getEnabledProperty("captureBindParameters");
    private static final BooleanProperty captureStatementClose =
            pluginServices.getEnabledProperty("captureStatementClose");

    // ===================== Mixin =====================

    @Mixin({"java.sql.Statement", "java.sql.ResultSet"})
    public static class HasStatementMirrorImpl implements HasStatementMirror {
        // the field and method names are verbose to avoid conflict since they will become fields
        // and methods in all classes that extend java.sql.Statement or java.sql.ResultSet
        //
        // does not need to be volatile, app/framework must provide visibility of Statements and
        // ResultSets if used across threads and this can piggyback
        private @Nullable StatementMirror glowroot$statementMirror;
        @Override
        public @Nullable StatementMirror glowroot$getStatementMirror() {
            return glowroot$statementMirror;
        }
        @Override
        public void glowroot$setStatementMirror(@Nullable StatementMirror statementMirror) {
            this.glowroot$statementMirror = statementMirror;
        }
        @Override
        public boolean glowroot$hasStatementMirror() {
            return glowroot$statementMirror != null;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.sql.Statement
    public interface HasStatementMirror {
        @Nullable
        StatementMirror glowroot$getStatementMirror();
        void glowroot$setStatementMirror(@Nullable StatementMirror statementMirror);
        boolean glowroot$hasStatementMirror();
    }

    // ================= Parameter Binding =================

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setArray|setBigDecimal"
            + "|setBoolean|setByte|setDate|setDouble|setFloat|setInt|setLong|setNString"
            + "|setRef|setRowId|setShort|setString|setTime|setTimestamp|setURL",
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
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                mirror.setParameterValue(parameterIndex, x);
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setAsciiStream"
            + "|setBinaryStream|setBlob|setCharacterStream|setClob|setNCharacterStream|setNClob"
            + "|setSQLXML|setUnicodeStream",
            methodParameterTypes = {"int", "*", ".."})
    public static class SetStreamAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement,
                @BindParameter int parameterIndex, @BindParameter @Nullable Object x) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                if (x == null) {
                    mirror.setParameterValue(parameterIndex, null);
                } else {
                    mirror.setParameterValue(parameterIndex,
                            new StreamingParameterValue(x.getClass()));
                }
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setBytes",
            methodParameterTypes = {"int", "byte[]"})
    public static class SetBytesAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement,
                @BindParameter int parameterIndex, @BindParameter byte/*@Nullable*/[] x) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                if (x == null) {
                    mirror.setParameterValue(parameterIndex, null);
                } else {
                    setBytes(mirror, parameterIndex, x);
                }
            }
        }
        private static void setBytes(PreparedStatementMirror mirror, int parameterIndex, byte[] x) {
            boolean displayAsHex = JdbcPluginProperties.displayBinaryParameterAsHex(
                    mirror.getSql(), parameterIndex);
            mirror.setParameterValue(parameterIndex, new ByteArrayParameterValue(x, displayAsHex));
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "setObject",
            methodParameterTypes = {"int", "java.lang.Object", ".."})
    public static class SetObjectAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureBindParameters.value();
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement,
                @BindParameter int parameterIndex, @BindParameter @Nullable Object x) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                if (x == null) {
                    mirror.setParameterValue(parameterIndex, null);
                } else if (x instanceof byte[]) {
                    SetBytesAdvice.setBytes(mirror, parameterIndex, (byte[]) x);
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
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                mirror.setParameterValue(parameterIndex, null);
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
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror != null) {
                mirror.clearParameters();
            }
        }
    }

    // ================== Statement Batching ==================

    @Pointcut(className = "java.sql.Statement", methodName = "addBatch",
            methodParameterTypes = {"java.lang.String"})
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror != null) {
                mirror.addBatch(sql);
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "addBatch",
            methodParameterTypes = {})
    public static class PreparedStatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror preparedStatement) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
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
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror != null) {
                mirror.clearBatch();
            }
        }
    }

    // =================== Statement Execution ===================

    @Pointcut(className = "java.sql.Statement", methodName = "execute",
            methodParameterTypes = {"java.lang.String", ".."}, ignoreSelfNested = true,
            timerName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                return null;
            }
            if (pluginServices.isEnabled()) {
                MessageSupplier messageSupplier = new StatementMessageSupplier(sql);
                QueryEntry query = pluginServices.startQueryEntry(QUERY_TYPE, sql, messageSupplier,
                        timerName);
                mirror.setLastQuery(query);
                return query;
            } else {
                // clear lastJdbcQuery so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.clearLastQuery();
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeQuery",
            methodParameterTypes = {"java.lang.String"}, methodReturnType = "java.sql.ResultSet",
            ignoreSelfNested = true, timerName = "jdbc execute")
    public static class StatementExecuteQueryAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            return StatementExecuteAdvice.onBefore(statement, sql);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror statement,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            // Statement can always be retrieved from ResultSet.getStatement(), and
            // StatementMirror from that, but ResultSet.getStatement() is sometimes not super
            // duper fast due to ResultSet wrapping and other checks, so StatementMirror is
            // stored directly in ResultSet as an optimization
            if (resultSet != null) {
                StatementMirror mirror = statement.glowroot$getStatementMirror();
                resultSet.glowroot$setStatementMirror(mirror);
            }
            if (queryEntry != null) {
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeUpdate",
            methodParameterTypes = {"java.lang.String", ".."}, methodReturnType = "int",
            ignoreSelfNested = true, timerName = "jdbc execute")
    public static class StatementExecuteUpdateAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(@BindReceiver HasStatementMirror statement,
                @BindParameter @Nullable String sql) {
            return StatementExecuteAdvice.onBefore(statement, sql);
        }
        @OnReturn
        public static void onReturn(@BindReturn int rowCount,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.setCurrRow(rowCount);
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "execute",
            methodParameterTypes = {}, ignoreSelfNested = true, timerName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(
                @BindReceiver HasStatementMirror preparedStatement) {
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                return null;
            }
            if (pluginServices.isEnabled()) {
                MessageSupplier messageSupplier;
                String queryText = mirror.getSql();
                if (captureBindParameters.value()) {
                    messageSupplier = new PreparedStatementMessageSupplier(queryText,
                            mirror.getParametersCopy());
                } else {
                    messageSupplier = new StatementMessageSupplier(queryText);
                }
                QueryEntry queryEntry = pluginServices.startQueryEntry(QUERY_TYPE, queryText,
                        messageSupplier, timerName);
                mirror.setLastQuery(queryEntry);
                return queryEntry;
            } else {
                // clear lastJdbcQuery so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
                mirror.clearLastQuery();
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "executeQuery",
            methodParameterTypes = {}, methodReturnType = "java.sql.ResultSet",
            ignoreSelfNested = true, timerName = "jdbc execute")
    public static class PreparedStatementExecuteQueryAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(
                @BindReceiver HasStatementMirror preparedStatement) {
            return PreparedStatementExecuteAdvice.onBefore(preparedStatement);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror preparedStatement,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            // PreparedStatement can always be retrieved from ResultSet.getStatement(), and
            // StatementMirror from that, but ResultSet.getStatement() is sometimes not super
            // duper fast due to ResultSet wrapping and other checks, so StatementMirror is
            // stored directly in ResultSet as an optimization
            if (resultSet != null) {
                StatementMirror mirror = preparedStatement.glowroot$getStatementMirror();
                resultSet.glowroot$setStatementMirror(mirror);
            }
            if (queryEntry != null) {
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "executeUpdate",
            methodParameterTypes = {}, methodReturnType = "int", ignoreSelfNested = true,
            timerName = "jdbc execute")
    public static class PreparedStatementExecuteUpdateAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(
                @BindReceiver HasStatementMirror preparedStatement) {
            return PreparedStatementExecuteAdvice.onBefore(preparedStatement);
        }
        @OnReturn
        public static void onReturn(@BindReturn int rowCount,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.setCurrRow(rowCount);
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
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
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(@BindReceiver HasStatementMirror statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror =
                        (PreparedStatementMirror) statement.glowroot$getStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                    return null;
                }
                return onBeforePreparedStatement(mirror);
            } else {
                StatementMirror mirror = statement.glowroot$getStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                    return null;
                }
                return onBeforeStatement(mirror);
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn int[] rowCounts,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                int totalRowCount = 0;
                for (int rowCount : rowCounts) {
                    totalRowCount += rowCount;
                }
                queryEntry.setCurrRow(totalRowCount);
                queryEntry.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(ErrorMessage.from(t));
            }
        }
        private static @Nullable QueryEntry onBeforePreparedStatement(
                PreparedStatementMirror mirror) {
            if (pluginServices.isEnabled()) {
                MessageSupplier messageSupplier;
                String queryText = mirror.getSql();
                int batchSize = mirror.getBatchSize();
                if (captureBindParameters.value()) {
                    messageSupplier = new BatchPreparedStatementMessageSupplier(queryText,
                            mirror.getBatchedParameters());
                } else {
                    messageSupplier = new BatchPreparedStatementMessageSupplier2(queryText,
                            batchSize);
                }
                QueryEntry queryEntry = pluginServices.startQueryEntry(QUERY_TYPE, queryText,
                        batchSize, messageSupplier, timerName);
                mirror.setLastQuery(queryEntry);
                mirror.clearBatch();
                return queryEntry;
            } else {
                // clear lastJdbcQuery so that its numRows won't be updated if the
                // plugin is re-enabled in the middle of iterating over a different result set
                mirror.clearLastQuery();
                return null;
            }
        }
        private static @Nullable QueryEntry onBeforeStatement(StatementMirror mirror) {
            if (pluginServices.isEnabled()) {
                MessageSupplier messageSupplier =
                        new BatchStatementMessageSupplier(mirror.getBatchedSql());
                QueryEntry queryEntry = pluginServices.startQueryEntry(QUERY_TYPE, "<batch sql>",
                        messageSupplier, timerName);
                mirror.setLastQuery(queryEntry);
                mirror.clearBatch();
                return queryEntry;
            } else {
                // clear lastJdbcQuery so that its numRows won't be updated if the
                // plugin is re-enabled in the middle of iterating over a different result set
                mirror.clearLastQuery();
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
            return statement.glowroot$hasStatementMirror();
        }
        @OnReturn
        public static void onReturn(@BindReturn HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            resultSet.glowroot$setStatementMirror(mirror);
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
            return statement.glowroot$hasStatementMirror() && pluginServices.isEnabled();
        }
        @OnBefore
        public static @Nullable Timer onBefore(
                @BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror != null) {
                // this should always be true since just checked hasGlowrootStatementMirror() above
                mirror.clearLastQuery();
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
