/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.jdbc;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindMethodName;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;
import io.informant.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import io.informant.plugin.jdbc.PreparedStatementMirror.NullParameterValue;
import io.informant.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;
import io.informant.shaded.slf4j.Logger;
import io.informant.shaded.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import checkers.nullness.quals.Nullable;

/**
 * Defines pointcuts to capture data on {@link Statement}, {@link PreparedStatement},
 * {@link CallableStatement} and {@link ResultSet} calls.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// many of the pointcuts are not restricted to pluginServices.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class JdbcAspect {

    private static final Logger logger = LoggerFactory.getLogger(JdbcAspect.class);

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:jdbc-plugin");

    private static final AtomicBoolean noSqlTextAvailableLoggedOnce = new AtomicBoolean();

    private static volatile int stackTraceThresholdMillis;
    private static volatile boolean captureBindParameters;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            public void onChange() {
                Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
                captureBindParameters = pluginServices.getBooleanProperty("captureBindParameters");
            }
        });
        Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
        stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
        captureBindParameters = pluginServices.getBooleanProperty("captureBindParameters");
    }

    // ===================== Mixin =====================

    @Mixin(target = "java.sql.Statement")
    public static class HasStatementMirrorImpl implements HasStatementMirror {
        @Nullable
        private volatile StatementMirror statementMirror;
        @Nullable
        public StatementMirror getInformantStatementMirror() {
            return statementMirror;
        }
        public void setInformantStatementMirror(StatementMirror statementMirror) {
            this.statementMirror = statementMirror;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.sql.Statement
    public interface HasStatementMirror {
        @Nullable
        StatementMirror getInformantStatementMirror();
        void setInformantStatementMirror(StatementMirror statementMirror);
    }

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(typeName = "java.sql.Connection", methodName = "prepare*",
            methodArgs = {"java.lang.String", ".."}, captureNested = false)
    public static class PrepareStatementTrackingAdvice {
        @OnReturn
        public static void onReturn(@BindReturn PreparedStatement preparedStatement,
                @BindMethodArg String sql) {
            ((HasStatementMirror) preparedStatement)
                    .setInformantStatementMirror(new PreparedStatementMirror(sql));
        }
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "prepare*",
            methodArgs = {"java.lang.String", ".."}, captureNested = false,
            metricName = "jdbc prepare")
    public static class PrepareStatementTimingAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(PrepareStatementTimingAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodArgs = {"int", "*", ".."}, captureNested = false)
    public static class PreparedStatementSetXAdvice {
        @OnReturn
        public static void onReturn(@BindTarget PreparedStatement preparedStatement,
                @BindMethodArg int parameterIndex, @BindMethodArg Object x) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (x instanceof InputStream || x instanceof Reader) {
                mirror.setParameterValue(parameterIndex, new StreamingParameterValue(x));
            } else if (x instanceof byte[]) {
                boolean displayAsHex = JdbcPluginProperties.displayBinaryParameterAsHex(
                        mirror.getSql(), parameterIndex);
                mirror.setParameterValue(parameterIndex, new ByteArrayParameterValue((byte[]) x,
                        displayAsHex));
            } else {
                mirror.setParameterValue(parameterIndex, x);
            }
        }
    }

    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "setNull",
            methodArgs = {"int", "int", ".."}, captureNested = false)
    public static class PreparedStatementSetNullAdvice {
        @OnReturn
        public static void onReturn(@BindTarget PreparedStatement preparedStatement,
                @BindMethodArg int parameterIndex) {
            getPreparedStatementMirror(preparedStatement).setParameterValue(parameterIndex,
                    new NullParameterValue());
        }
    }

    // ================== Statement Batching ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "addBatch",
            methodArgs = {"java.lang.String"}, captureNested = false)
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindTarget Statement statement,
                @BindMethodArg String sql) {
            getStatementMirror(statement).addBatch(sql);
        }
    }

    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "addBatch",
            captureNested = false)
    public static class PreparedStatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@BindTarget PreparedStatement preparedStatement) {
            getPreparedStatementMirror(preparedStatement).addBatch();
        }
    }

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut(typeName = "java.sql.Statement", methodName = "clearBatch")
    public static class StatementClearBatchAdvice {
        @OnReturn
        public static void onReturn(@BindTarget Statement statement) {
            StatementMirror mirror = getStatementMirror(statement);
            mirror.clearBatch();
        }
    }

    // =================== Statement Execution ===================

    @Pointcut(typeName = "java.sql.Statement", methodName = "execute*",
            methodArgs = {"java.lang.String", ".."}, captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindTarget Statement statement,
                @BindMethodArg String sql) {
            StatementMirror mirror = getStatementMirror(statement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier.create(sql,
                        getConnectionHashCode(statement));
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metricName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't get incorrectly updated
                // if the plugin is re-enabled in the middle of iterating over a different
                // result set
                //
                // TODO implement test, same statement, execute multiple queries,
                // disable/re-enable informant in between two of them, check row counts
                mirror.setLastJdbcMessageSupplier(null);
                return null;
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(typeName = "java.sql.PreparedStatement",
            methodName = "execute|executeQuery|executeUpdate", captureNested = false,
            metricName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindTarget PreparedStatement preparedStatement) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier;
                if (captureBindParameters) {
                    jdbcMessageSupplier = JdbcMessageSupplier.createWithParameters(mirror,
                            getConnectionHashCode(preparedStatement));
                } else {
                    jdbcMessageSupplier = JdbcMessageSupplier.create(mirror.getSql(),
                            getConnectionHashCode(preparedStatement));
                }
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metricName);
            } else {
                // clear lastJdbcMessageSupplier so that its numRows won't get incorrectly updated
                // if the plugin is re-enabled in the middle of iterating over a different
                // result set
                //
                // TODO implement test, same prepared statement, execute multiple queries,
                // disable/re-enable informant in between two of them, check row counts
                mirror.setLastJdbcMessageSupplier(null);
                return null;
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
    }

    @Pointcut(typeName = "java.sql.Statement", methodName = "executeBatch", captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(StatementExecuteBatchAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindTarget Statement statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror =
                        getPreparedStatementMirror((PreparedStatement) statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier;
                    if (captureBindParameters) {
                        jdbcMessageSupplier = JdbcMessageSupplier.createWithBatchedParameters(
                                mirror, getConnectionHashCode(statement));
                    } else {
                        jdbcMessageSupplier = JdbcMessageSupplier.create(mirror.getSql(),
                                getConnectionHashCode(statement));
                    }
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metricName);
                } else {
                    // clear lastJdbcMessageSupplier so that its numRows won't get incorrectly
                    // updated if the plugin is re-enabled in the middle of iterating over a
                    // different result set
                    //
                    // TODO implement test, same prepared statement, execute multiple queries,
                    // disable/re-enable informant in between two of them, check row counts
                    mirror.setLastJdbcMessageSupplier(null);
                    return null;
                }
            } else {
                StatementMirror mirror = getStatementMirror(statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier
                            .createWithBatchedSqls(mirror, getConnectionHashCode(statement));
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metricName);
                } else {
                    // clear lastJdbcMessageSupplier so that its numRows won't get incorrectly
                    // updated if the plugin is re-enabled in the middle of iterating over a
                    // different result set
                    //
                    // TODO implement test, same prepared statement, execute multiple queries,
                    // disable/re-enable informant in between two of them, check row counts
                    mirror.setLastJdbcMessageSupplier(null);
                    return null;
                }
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
    }

    // ========= ResultSet =========

    // TODO support ResultSet.relative(), absolute() and last()

    // capture the row number any time the cursor is moved through the result set

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "next", captureNested = false,
            metricName = "jdbc resultset next")
    public static class ResultSetNextAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ResultSetNextAdvice.class);
        private static volatile boolean pluginEnabled;
        // plugin configuration property captureResultSetNext is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    metricEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("captureResultSetNext");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            metricEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureResultSetNext");
        }
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginEnabled && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static MetricTimer onBefore() {
            if (metricEnabled) {
                return pluginServices.startMetricTimer(metricName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn boolean currentRowValid,
                @BindTarget ResultSet resultSet) {
            try {
                Statement statement = resultSet.getStatement();
                if (statement == null) {
                    // this is not a statement execution, it is some other execution of
                    // ResultSet.next(), e.g. Connection.getMetaData().getTables().next()
                    return;
                }
                StatementMirror mirror = getStatementMirror(statement);
                JdbcMessageSupplier lastJdbcMessageSupplier = mirror.getLastJdbcMessageSupplier();
                if (lastJdbcMessageSupplier == null) {
                    // tracing must be disabled (e.g. exceeded span limit per trace)
                    return;
                }
                if (currentRowValid) {
                    lastJdbcMessageSupplier.setNumRows(resultSet.getRow());
                    // TODO also record time spent in next() into JdbcMessageSupplier
                } else {
                    lastJdbcMessageSupplier.setHasPerformedNext();
                }
            } catch (SQLException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable MetricTimer metricTimer) {
            if (metricTimer != null) {
                metricTimer.stop();
            }
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "get*", methodArgs = {"int", ".."},
            metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ResultSetValueAdvice.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return metricEnabled && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "get*",
            methodArgs = {"java.lang.String", ".."}, metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice2 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ResultSetValueAdvice2.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return metricEnabled && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ========= Transactions =========

    @Pointcut(typeName = "java.sql.Connection", methodName = "commit", captureNested = false,
            metricName = "jdbc commit")
    public static class ConnectionCommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ConnectionCommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindTarget Connection connection) {
            return pluginServices.startSpan(MessageSupplier.from("jdbc commit [connection: {}]",
                    Integer.toHexString(connection.hashCode())), metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span) {
            span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "close", captureNested = false,
            metricName = "jdbc statement close")
    public static class StatementCloseAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(StatementCloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore(@BindTarget Statement statement) {
            // help out gc a little by clearing the weak reference, don't want to solely rely on
            // this (and use strong reference) in case a jdbc driver implementation closes
            // statements in finalize by calling an internal method and not calling public close()
            getStatementMirror(statement).setLastJdbcMessageSupplier(null);
            return pluginServices.startMetricTimer(metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ================== Metadata ==================

    @Pointcut(typeName = "java.sql.DatabaseMetaData", methodName = "*", methodArgs = {".."},
            captureNested = false, metricName = "jdbc metadata")
    public static class DatabaseMetaDataAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(DatabaseMetaDataAdvice.class);
        // DatabaseMetaData method timings are captured below, so this thread local is used to
        // avoid capturing driver-specific java.sql.Statement executions used to implement the
        // method internally (especially since it is haphazard whether a particular driver
        // internally uses a java.sql API that is woven, or an internal API, or even a mis-matched
        // combination like using a PreparedStatement but not creating it via
        // Connection.prepareStatement())
        private static final ThreadLocal</*@Nullable*/String> inDatabaseMetataDataMethod =
                new ThreadLocal</*@Nullable*/String>();
        // plugin configuration property captureDatabaseMetaDataSpans is cached to limit map lookups
        private static volatile boolean pluginEnabled;
        private static volatile boolean spanEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    spanEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("captureDatabaseMetaDataSpans");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            spanEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureDatabaseMetaDataSpans");
        }
        @OnBefore
        @Nullable
        public static Object onBefore(@BindTarget DatabaseMetaData databaseMetaData,
                @BindMethodName String methodName) {
            inDatabaseMetataDataMethod.set(methodName);
            if (pluginServices.isEnabled()) {
                if (spanEnabled) {
                    return pluginServices.startSpan(MessageSupplier.from("jdbc metadata:"
                            + " DatabaseMetaData.{}() [connection: {}]", methodName,
                            getConnectionHashCode(databaseMetaData)), metricName);
                } else {
                    return pluginServices.startMetricTimer(metricName);
                }
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Object spanOrTimer) {
            // don't need to track prior value and reset to that value, since
            // @Pointcut.captureNested = false prevents re-entrant calls
            inDatabaseMetataDataMethod.remove();
            if (spanOrTimer == null) {
                return;
            }
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).end();
            } else {
                ((MetricTimer) spanOrTimer).stop();
            }
        }
    }

    private static StatementMirror getStatementMirror(Statement statement) {
        StatementMirror mirror = ((HasStatementMirror) statement).getInformantStatementMirror();
        if (mirror == null) {
            mirror = new StatementMirror();
            ((HasStatementMirror) statement).setInformantStatementMirror(mirror);
        }
        return mirror;
    }

    private static PreparedStatementMirror getPreparedStatementMirror(
            PreparedStatement preparedStatement) {
        PreparedStatementMirror mirror = (PreparedStatementMirror)
                ((HasStatementMirror) preparedStatement).getInformantStatementMirror();
        if (mirror == null) {
            String methodName = DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get();
            if (methodName != null) {
                // wrapping description in sql comment (/* */)
                mirror = new PreparedStatementMirror("/* internal prepared statement generated by"
                        + " java.sql.DatabaseMetaData." + methodName + "() */");
                ((HasStatementMirror) preparedStatement).setInformantStatementMirror(mirror);
            } else {
                // wrapping description in sql comment (/* */)
                mirror = new PreparedStatementMirror("/* prepared statement generated outside of"
                        + " the java.sql.Connection.prepare*() public API, no sql text available"
                        + " */");
                ((HasStatementMirror) preparedStatement).setInformantStatementMirror(mirror);
                if (!noSqlTextAvailableLoggedOnce.getAndSet(true)) {
                    // this is only logged the first time it occurs
                    logger.warn("prepared statement generated outside of the"
                            + " java.sql.Connection.prepare*() public API, no sql text available",
                            new Throwable());
                }
            }
        }
        return mirror;
    }

    // return Integer (as opposed to DatabaseMetaData method below) in order to delay the
    // hex conversion until/if needed
    @Nullable
    private static Integer getConnectionHashCode(Statement statement) {
        try {
            return statement.getConnection().hashCode();
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }

    private static String getConnectionHashCode(DatabaseMetaData databaseMetaData) {
        try {
            return Integer.toHexString(databaseMetaData.getConnection().hashCode());
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            return "???";
        }
    }
}
