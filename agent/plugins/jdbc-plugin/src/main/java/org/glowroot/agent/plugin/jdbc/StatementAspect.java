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

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.glowroot.agent.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;
import org.glowroot.agent.plugin.jdbc.message.BatchPreparedStatementMessageSupplier;
import org.glowroot.agent.plugin.jdbc.message.BatchPreparedStatementMessageSupplier2;
import org.glowroot.agent.plugin.jdbc.message.PreparedStatementMessageSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

// many of the pointcuts are not restricted to configService.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class StatementAspect {

    private static final String QUERY_TYPE = "SQL";

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty captureBindParameters =
            configService.getBooleanProperty("captureBindParameters");
    private static final BooleanProperty captureStatementClose =
            configService.getBooleanProperty("captureStatementClose");

    @Shim("java.sql.PreparedStatement")
    public interface PreparedStatement {}

    // ===================== Mixin =====================

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"java.sql.Statement", "java.sql.ResultSet"})
    public static class HasStatementMirrorImpl implements HasStatementMirror {
        // does not need to be volatile, app/framework must provide visibility of Statements and
        // ResultSets if used across threads and this can piggyback
        private @Nullable StatementMirror glowroot$statementMirror;
        @Override
        public @Nullable StatementMirror glowroot$getStatementMirror() {
            return glowroot$statementMirror;
        }
        @Override
        public void glowroot$setStatementMirror(@Nullable StatementMirror statementMirror) {
            glowroot$statementMirror = statementMirror;
        }
        @Override
        public boolean glowroot$hasStatementMirror() {
            return glowroot$statementMirror != null;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface HasStatementMirror {
        @Nullable
        StatementMirror glowroot$getStatementMirror();
        void glowroot$setStatementMirror(@Nullable StatementMirror statementMirror);
        boolean glowroot$hasStatementMirror();
    }

    // ================= Parameter Binding =================

    @Pointcut(className = "java.sql.PreparedStatement",
            methodName = "setArray|setBigDecimal"
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

    @Pointcut(className = "java.sql.PreparedStatement",
            methodName = "setAsciiStream|setBinaryStream|setBlob|setCharacterStream|setClob"
                    + "|setNCharacterStream|setNClob|setSQLXML|setUnicodeStream",
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
                @BindParameter int parameterIndex, @BindParameter byte /*@Nullable*/ [] x) {
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
            boolean displayAsHex = JdbcPluginProperties.displayBinaryParameterAsHex(mirror.getSql(),
                    parameterIndex);
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
            methodParameterTypes = {"java.lang.String", ".."}, nestingGroup = "jdbc",
            timerName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror statement, @BindParameter @Nullable String sql) {
            if (sql == null) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked hasGlowrootStatementMirror() above
                return null;
            }
            QueryEntry query = context.startQueryEntry(QUERY_TYPE, sql,
                    QueryMessageSupplier.create("jdbc execution: "), timerName);
            mirror.setLastQueryEntry(query);
            return query;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithLocationStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeQuery",
            methodParameterTypes = {"java.lang.String"}, methodReturnType = "java.sql.ResultSet",
            nestingGroup = "jdbc", timerName = "jdbc execute")
    public static class StatementExecuteQueryAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror statement, @BindParameter @Nullable String sql) {
            return StatementExecuteAdvice.onBefore(context, statement, sql);
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
                queryEntry.endWithLocationStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeUpdate",
            methodParameterTypes = {"java.lang.String", ".."}, methodReturnType = "int",
            nestingGroup = "jdbc", timerName = "jdbc execute")
    public static class StatementExecuteUpdateAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror statement, @BindParameter @Nullable String sql) {
            return StatementExecuteAdvice.onBefore(context, statement, sql);
        }
        @OnReturn
        public static void onReturn(@BindReturn int rowCount,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.setCurrRow(rowCount);
                queryEntry.endWithLocationStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "jdbc", timerName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final TimerName timerName =
                Agent.getTimerName(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror preparedStatement) {
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @Nonnull
            PreparedStatementMirror mirror =
                    (PreparedStatementMirror) preparedStatement.glowroot$getStatementMirror();
            QueryMessageSupplier queryMessageSupplier;
            String queryText = mirror.getSql();
            if (captureBindParameters.value()) {
                queryMessageSupplier = new PreparedStatementMessageSupplier(mirror.getParameters());
            } else {
                queryMessageSupplier = QueryMessageSupplier.create("jdbc execution: ");
            }
            QueryEntry queryEntry =
                    context.startQueryEntry(QUERY_TYPE, queryText, queryMessageSupplier, timerName);
            mirror.setLastQueryEntry(queryEntry);
            return queryEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler QueryEntry queryEntry) {
            queryEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler QueryEntry queryEntry) {
            queryEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "executeQuery",
            methodParameterTypes = {}, methodReturnType = "java.sql.ResultSet",
            nestingGroup = "jdbc", timerName = "jdbc execute")
    public static class PreparedStatementExecuteQueryAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror preparedStatement) {
            return PreparedStatementExecuteAdvice.onBefore(context, preparedStatement);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror preparedStatement,
                @BindTraveler QueryEntry queryEntry) {
            // PreparedStatement can always be retrieved from ResultSet.getStatement(), and
            // StatementMirror from that, but ResultSet.getStatement() is sometimes not super
            // duper fast due to ResultSet wrapping and other checks, so StatementMirror is
            // stored directly in ResultSet as an optimization
            if (resultSet != null) {
                StatementMirror mirror = preparedStatement.glowroot$getStatementMirror();
                resultSet.glowroot$setStatementMirror(mirror);
            }
            queryEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler QueryEntry queryEntry) {
            queryEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.PreparedStatement", methodName = "executeUpdate",
            methodParameterTypes = {}, methodReturnType = "int", nestingGroup = "jdbc",
            timerName = "jdbc execute")
    public static class PreparedStatementExecuteUpdateAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror preparedStatement) {
            return preparedStatement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror preparedStatement) {
            return PreparedStatementExecuteAdvice.onBefore(context, preparedStatement);
        }
        @OnReturn
        public static void onReturn(@BindReturn int rowCount,
                @BindTraveler QueryEntry queryEntry) {
            queryEntry.setCurrRow(rowCount);
            queryEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler QueryEntry queryEntry) {
            queryEntry.endWithError(t);
        }
    }

    @Pointcut(className = "java.sql.Statement", methodName = "executeBatch",
            methodParameterTypes = {}, nestingGroup = "jdbc", timerName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final TimerName timerName =
                Agent.getTimerName(StatementExecuteBatchAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static QueryEntry onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror statement) {
            @SuppressWarnings("nullness") // just checked above in isEnabled()
            @Nonnull
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (statement instanceof PreparedStatement) {
                return onBeforePreparedStatement(context, (PreparedStatementMirror) mirror);
            } else {
                return onBeforeStatement(mirror, context);
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn int[] rowCounts,
                @BindTraveler QueryEntry queryEntry) {
            int totalRowCount = 0;
            boolean count = false;
            for (int rowCount : rowCounts) {
                if (rowCount > 0) {
                    // ignore Statement.SUCCESS_NO_INFO (-2) and Statement.EXECUTE_FAILED (-3)
                    totalRowCount += rowCount;
                    count = true;
                }
            }
            if (count) {
                queryEntry.setCurrRow(totalRowCount);
            }
            queryEntry.endWithLocationStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                    MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler QueryEntry queryEntry) {
            queryEntry.endWithError(t);
        }
        private static QueryEntry onBeforePreparedStatement(ThreadContext context,
                PreparedStatementMirror mirror) {
            QueryMessageSupplier queryMessageSupplier;
            String queryText = mirror.getSql();
            int batchSize = mirror.getBatchSize();
            if (captureBindParameters.value()) {
                queryMessageSupplier = new BatchPreparedStatementMessageSupplier(
                        mirror.getBatchedParameters(), batchSize);
            } else {
                queryMessageSupplier = new BatchPreparedStatementMessageSupplier2(batchSize);
            }
            QueryEntry queryEntry = context.startQueryEntry(QUERY_TYPE, queryText, batchSize,
                    queryMessageSupplier, timerName);
            mirror.setLastQueryEntry(queryEntry);
            mirror.clearBatch();
            return queryEntry;
        }
        private static QueryEntry onBeforeStatement(StatementMirror mirror, ThreadContext context) {
            List<String> batchedSql = mirror.getBatchedSql();
            String concatenated;
            if (batchedSql.isEmpty()) {
                concatenated = "[empty batch]";
            } else {
                StringBuilder sb = new StringBuilder("[batch] ");
                boolean first = true;
                for (String sql : batchedSql) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(sql);
                    first = false;
                }
                concatenated = sb.toString();
            }
            QueryEntry queryEntry = context.startQueryEntry(QUERY_TYPE, concatenated,
                    QueryMessageSupplier.create("jdbc execution: "), timerName);
            mirror.setLastQueryEntry(queryEntry);
            mirror.clearBatch();
            return queryEntry;
        }
    }

    // ================== Additional ResultSet Tracking ==================

    @Pointcut(className = "java.sql.Statement", methodName = "getResultSet",
            methodParameterTypes = {".."}, methodReturnType = "java.sql.ResultSet")
    public static class StatementGetResultSetAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HasStatementMirror resultSet,
                @BindReceiver HasStatementMirror statement) {
            if (resultSet == null) {
                return;
            }
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            resultSet.glowroot$setStatementMirror(mirror);
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(className = "java.sql.Statement", methodName = "close", methodParameterTypes = {},
            nestingGroup = "jdbc", timerName = "jdbc statement close")
    public static class CloseAdvice {
        private static final TimerName timerName = Agent.getTimerName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror statement) {
            return statement.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static @Nullable Timer onBefore(ThreadContext context,
                @BindReceiver HasStatementMirror statement) {
            StatementMirror mirror = statement.glowroot$getStatementMirror();
            if (mirror != null) {
                // this should always be true since just checked hasGlowrootStatementMirror() above
                mirror.clearLastQueryEntry();
            }
            if (captureStatementClose.value()) {
                return context.startTimer(timerName);
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
