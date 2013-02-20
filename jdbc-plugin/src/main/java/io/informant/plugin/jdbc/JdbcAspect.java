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

import io.informant.api.ErrorMessage;
import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.api.weaving.InjectMethodArg;
import io.informant.api.weaving.InjectMethodName;
import io.informant.api.weaving.InjectReturn;
import io.informant.api.weaving.InjectTarget;
import io.informant.api.weaving.InjectThrowable;
import io.informant.api.weaving.InjectTraveler;
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

import java.io.InputStream;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import checkers.nullness.quals.Nullable;

/**
 * Defines pointcuts to capture data on {@link Statement}, {@link PreparedStatement},
 * {@link CallableStatement} and {@link ResultSet} calls.
 * 
 * All pointcuts use !cflowbelow() constructs in order to pick out only top-level executions since
 * often jdbc drivers are exposed by application servers via wrappers (this is primarily useful for
 * runtime weaving which exposes these application server proxies to the weaving process).
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

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            public void onChange() {
                Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
        Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
        stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
    }

    @Mixin(target = "java.sql.Statement", mixin = HasStatementMirror.class,
            mixinImpl = HasStatementMirrorImpl.class)
    public static class MixinAdvice {}

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(typeName = "java.sql.Connection", methodName = "prepare*",
            methodArgs = { "java.lang.String", ".." }, captureNested = false)
    public static class PrepareStatementTrackingAdvice {
        @OnReturn
        public static void onReturn(@InjectReturn PreparedStatement preparedStatement,
                @InjectMethodArg String sql) {
            ((HasStatementMirror) preparedStatement)
                    .setInformantStatementMirror(new PreparedStatementMirror(sql));
        }
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "prepare*",
            methodArgs = { "java.lang.String", ".." }, captureNested = false,
            metricName = "jdbc prepare")
    public static class PrepareStatementTimingAdvice {
        private static final Metric metric = pluginServices
                .getMetric(PrepareStatementTimingAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore() {
            return pluginServices.startMetricTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodArgs = { "int", "*", ".." }, captureNested = false)
    public static class PreparedStatementSetXAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget PreparedStatement preparedStatement,
                @InjectMethodArg int parameterIndex, @InjectMethodArg Object x) {
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
            methodArgs = { "int", "int", ".." }, captureNested = false)
    public static class PreparedStatementSetNullAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget PreparedStatement preparedStatement,
                @InjectMethodArg int parameterIndex) {
            getPreparedStatementMirror(preparedStatement).setParameterValue(parameterIndex,
                    new NullParameterValue());
        }
    }

    // ================== Statement Batching ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "addBatch",
            methodArgs = { "java.lang.String" }, captureNested = false)
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget Statement statement,
                @InjectMethodArg String sql) {
            getStatementMirror(statement).addBatch(sql);
        }
    }

    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "addBatch",
            captureNested = false)
    public static class PreparedStatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget PreparedStatement preparedStatement) {
            getPreparedStatementMirror(preparedStatement).addBatch();
        }
    }

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut(typeName = "java.sql.Statement", methodName = "clearBatch")
    public static class StatementClearBatchAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget Statement statement) {
            StatementMirror mirror = getStatementMirror(statement);
            mirror.clearBatch();
        }
    }

    // =================== Statement Execution ===================

    @Pointcut(typeName = "java.sql.Statement", methodName = "execute*",
            methodArgs = { "java.lang.String", ".." }, captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final Metric metric = pluginServices.getMetric(StatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Statement statement,
                @InjectMethodArg String sql) {
            StatementMirror mirror = getStatementMirror(statement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier.create(sql,
                        getConnectionHashCode(statement));
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metric);
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
        public static void onThrow(@InjectThrowable Throwable t,
                @InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(typeName = "java.sql.PreparedStatement",
            methodName = "execute|executeQuery|executeUpdate", captureNested = false,
            metricName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final Metric metric = pluginServices
                .getMetric(PreparedStatementExecuteAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget PreparedStatement preparedStatement) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier
                        .createWithParameters(mirror, getConnectionHashCode(preparedStatement));
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startSpan(jdbcMessageSupplier, metric);
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
        public static void onThrow(@InjectThrowable Throwable t,
                @InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Pointcut(typeName = "java.sql.Statement", methodName = "executeBatch", captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final Metric metric = pluginServices
                .getMetric(StatementExecuteBatchAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Statement statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror =
                        getPreparedStatementMirror((PreparedStatement) statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier
                            .createWithBatchedParameters(mirror, getConnectionHashCode(statement));
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metric);
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
                    // TODO track all changes to statement mirrors regardless of isEnabled
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startSpan(jdbcMessageSupplier, metric);
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
        public static void onThrow(@InjectThrowable Throwable t,
                @InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithError(ErrorMessage.from(t));
            }
        }
        @OnReturn
        public static void onReturn(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.endWithStackTrace(stackTraceThresholdMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ========= ResultSet =========

    // TODO support ResultSet.relative(), absolute() and last()

    // capture the row number any time the cursor is moved through the result set

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "next", captureNested = false,
            metricName = "jdbc resultset next")
    public static class ResultSetNextAdvice {
        private static final Metric metric = pluginServices.getMetric(ResultSetNextAdvice.class);
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
                return pluginServices.startMetricTimer(metric);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@InjectReturn boolean currentRowValid,
                @InjectTarget ResultSet resultSet) {
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
                logger.error(e.getMessage(), e);
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable MetricTimer metricTimer) {
            if (metricTimer != null) {
                metricTimer.stop();
            }
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "get*", methodArgs = { "int", ".." },
            metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice.class);
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
            return pluginServices.startMetricTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "get*",
            methodArgs = { "java.lang.String", ".." }, metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice2 {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice2.class);
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
            return pluginServices.startMetricTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ========= Transactions =========

    @Pointcut(typeName = "java.sql.Connection", methodName = "commit", captureNested = false,
            metricName = "jdbc commit")
    public static class ConnectionCommitAdvice {
        private static final Metric metric = pluginServices.getMetric(ConnectionCommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Connection connection) {
            return pluginServices.startSpan(MessageSupplier.from("jdbc commit [connection: {}]",
                    Integer.toHexString(connection.hashCode())), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.endWithStackTrace(stackTraceThresholdMillis, TimeUnit.MILLISECONDS);
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "close", captureNested = false,
            metricName = "jdbc statement close")
    public static class StatementCloseAdvice {
        private static final Metric metric = pluginServices.getMetric(StatementCloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && DatabaseMetaDataAdvice.inDatabaseMetataDataMethod.get() == null;
        }
        @OnBefore
        public static MetricTimer onBefore(@InjectTarget Statement statement) {
            // help out gc a little by clearing the weak reference, don't want to solely rely on
            // this (and use strong reference) in case a jdbc driver implementation closes
            // statements in finalize by calling an internal method and not calling public close()
            getStatementMirror(statement).setLastJdbcMessageSupplier(null);
            return pluginServices.startMetricTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler MetricTimer metricTimer) {
            metricTimer.stop();
        }
    }

    // ================== Metadata ==================

    @Pointcut(typeName = "java.sql.DatabaseMetaData", methodName = "*", methodArgs = { ".." },
            captureNested = false, metricName = "jdbc metadata")
    public static class DatabaseMetaDataAdvice {
        private static final Metric metric = pluginServices.getMetric(DatabaseMetaDataAdvice.class);
        // DatabaseMetaData method timings are captured below, so this thread local is used to
        // avoid capturing driver-specific java.sql.Statement executions used to implement the
        // method internally (especially since it is haphazard whether a particular driver
        // internally uses a java.sql API that is woven, or an internal API, or even a mis-matched
        // combination like using a PreparedStatement but not creating it via
        // Connection.prepareStatement())
        private static final ThreadLocal</*@Nullable*/String> inDatabaseMetataDataMethod =
                new ThreadLocal</*@Nullable*/String>();
        // plugin configuration property spanForDatabaseMetaData is cached to limit map lookups
        private static volatile boolean pluginEnabled;
        private static volatile boolean spanEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    spanEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("spanForDatabaseMetaData");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            spanEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("spanForDatabaseMetaData");
        }
        @OnBefore
        @Nullable
        public static Object onBefore(@InjectTarget DatabaseMetaData databaseMetaData,
                @InjectMethodName String methodName) {
            inDatabaseMetataDataMethod.set(methodName);
            if (pluginServices.isEnabled()) {
                if (spanEnabled) {
                    return pluginServices.startSpan(MessageSupplier.from("jdbc metadata:"
                            + " DatabaseMetaData.{}() [connection: {}]", methodName,
                            getConnectionHashCode(databaseMetaData)), metric);
                } else {
                    return pluginServices.startMetricTimer(metric);
                }
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Object spanOrTimer) {
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
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private static String getConnectionHashCode(DatabaseMetaData databaseMetaData) {
        try {
            return Integer.toHexString(databaseMetaData.getConnection().hashCode());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return "???";
        }
    }
}
