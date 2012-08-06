/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.api.MessageSuppliers;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.PluginServices.ConfigListener;
import org.informantproject.api.Span;
import org.informantproject.api.Timer;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectMethodArg;
import org.informantproject.api.weaving.InjectMethodName;
import org.informantproject.api.weaving.InjectReturn;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.Mixin;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.Pointcut;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.NullParameterValue;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;

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
@Aspect
public class JdbcAspect {

    private static final Logger logger = LoggerFactory.getLogger(JdbcAspect.class);

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject.plugins:jdbc-plugin");

    private static final AtomicBoolean noSqlTextAvailableLoggedOnce = new AtomicBoolean();

    @Mixin(target = "java.sql.Statement", mixin = HasStatementMirror.class,
            mixinImpl = HasStatementMirrorImpl.class)
    public static class MixinAdvice {}

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut(typeName = "java.sql.Connection", methodName = "/prepare.*/",
            methodArgs = { "java.lang.String", ".." })
    public static class PrepareStatementTrackingAdvice {
        @OnReturn
        public static void onReturn(@InjectReturn PreparedStatement preparedStatement,
                @InjectMethodArg String sql) {

            ((HasStatementMirror) preparedStatement)
                    .setInformantStatementMirror(new PreparedStatementMirror(sql));
        }
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "/prepare.*/",
            methodArgs = { "java.lang.String", ".." }, metricName = "jdbc prepare")
    public static class PrepareStatementTimingAdvice {
        private static final Metric metric = pluginServices
                .getMetric(PrepareStatementTimingAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Timer timer) {
            timer.end();
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodArgs = { "int", "/.*/", ".." })
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
            methodArgs = { "int", "int", ".." })
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
            methodArgs = { "java.lang.String" })
    public static class StatementAddBatchAdvice {
        @OnReturn
        public static void onReturn(@InjectTarget Statement statement,
                @InjectMethodArg String sql) {

            getStatementMirror(statement).addBatch(sql);
        }
    }

    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "addBatch")
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

    @Pointcut(typeName = "java.sql.Statement", methodName = "/execute.*/",
            methodArgs = { "java.lang.String", ".." }, captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteAdvice {
        private static final Metric metric = pluginServices.getMetric(StatementExecuteAdvice.class);
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
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.end();
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(typeName = "java.sql.PreparedStatement",
            methodName = "/execute|executeQuery|executeUpdate/", captureNested = false,
            metricName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final Metric metric = pluginServices
                .getMetric(PreparedStatementExecuteAdvice.class);
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget PreparedStatement preparedStatement) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = JdbcMessageSupplier.createWithParameters(
                        mirror, getConnectionHashCode(preparedStatement));
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
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.end();
            }
        }
    }

    @Pointcut(typeName = "java.sql.Statement", methodName = "executeBatch", captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final Metric metric = pluginServices
                .getMetric(StatementExecuteBatchAdvice.class);
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget final Statement statement) {
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
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Span span) {
            if (span != null) {
                span.end();
            }
        }
    }

    // ========= ResultSet =========

    // TODO support ResultSet.relative(), absolute() and last()

    // capture the row number any time the cursor is moved through the result set

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "next",
            metricName = "jdbc resultset next")
    public static class ResultSetNextAdvice {
        private static final Metric metric = pluginServices.getMetric(ResultSetNextAdvice.class);
        private static volatile boolean pluginEnabled;
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
            return pluginEnabled;
        }
        @OnBefore
        @Nullable
        public static Timer onBefore() {
            if (metricEnabled) {
                return pluginServices.startTimer(metric);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@InjectReturn boolean currentRowValid,
                @InjectTarget final ResultSet resultSet) {

            try {
                if (resultSet.getStatement() == null) {
                    // this is not a statement execution, it is some other execution of
                    // ResultSet.next(), e.g. Connection.getMetaData().getTables().next()
                    return;
                }
                StatementMirror mirror = getStatementMirror(resultSet.getStatement());
                JdbcMessageSupplier lastSpan = mirror.getLastJdbcMessageSupplier();
                if (lastSpan == null) {
                    // tracing must be disabled (e.g. exceeded trace limit per operation)
                    return;
                }
                if (currentRowValid) {
                    lastSpan.setNumRows(resultSet.getRow());
                    // TODO also record time spent in next() into JdbcSpan
                } else {
                    lastSpan.setHasPerformedNext();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.end();
            }
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "/get.*/", methodArgs = { "int", ".." },
            metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice.class);
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
            return metricEnabled;
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Timer timer) {
            timer.end();
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "/get.*/",
            methodArgs = { "java.lang.String", ".." }, metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice2 {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice2.class);
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
            return metricEnabled;
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Timer timer) {
            timer.end();
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
            return pluginServices.startSpan(
                    MessageSuppliers.of("jdbc commit [connection: {{hashCode}}]",
                            Integer.toHexString(connection.hashCode())), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    // ================== Statement Closing ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "close",
            metricName = "jdbc statement close")
    public static class StatementCloseAdvice {
        private static final Metric metric = pluginServices.getMetric(StatementCloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Timer timer) {
            timer.end();
        }
    }

    // ================== Metadata ==================

    @Pointcut(typeName = "java.sql.DatabaseMetaData", methodName = "/.*/", methodArgs = { ".." },
            metricName = "jdbc metadata", captureNested = false)
    public static class DatabaseMetaDataAdvice {
        private static final Metric metric = pluginServices.getMetric(DatabaseMetaDataAdvice.class);
        private static final ThreadLocal<String> inDatabaseMetatDataCall =
                new ThreadLocal<String>();
        @OnBefore
        @Nullable
        public static Timer onBefore(@InjectMethodName String methodName) {
            inDatabaseMetatDataCall.set(methodName);
            if (pluginServices.isEnabled()) {
                return pluginServices.startTimer(metric);
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler @Nullable Timer timer) {
            // don't need to track prior value and reset to that value, since
            // @Pointcut.captureNested = false prevents re-entrant calls
            inDatabaseMetatDataCall.set(null);
            if (timer != null) {
                timer.end();
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
            String methodName = DatabaseMetaDataAdvice.inDatabaseMetatDataCall.get();
            if (methodName != null) {
                // wrapping description in sql comment /* */
                mirror = new PreparedStatementMirror("/* internal prepared statement generated by"
                        + " java.sql.DatabaseMetaData." + methodName + "() */");
                ((HasStatementMirror) preparedStatement).setInformantStatementMirror(mirror);
            } else {
                // wrapping description in sql comment /* */
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

    @Nullable
    private static Integer getConnectionHashCode(Statement statement) {
        try {
            return statement.getConnection().hashCode();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

}
