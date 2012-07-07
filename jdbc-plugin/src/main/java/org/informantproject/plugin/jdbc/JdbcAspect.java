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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.api.MessageSupplier;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.PluginServices.ConfigurationListener;
import org.informantproject.api.Stopwatch;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectMethodArg;
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

    private static final AtomicBoolean missingSqlTextErrorLogged = new AtomicBoolean();

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

            ((HasStatementMirror) preparedStatement).setInformantStatementMirror(
                    new PreparedStatementMirror(sql));
        }
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "/prepare.*/",
            methodArgs = { "java.lang.String", ".." }, metricName = "jdbc prepare")
    public static class PrepareStatementTimingAdvice {
        private static final Metric metric = pluginServices.getMetric(
                PrepareStatementTimingAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Stopwatch onBefore() {
            return metric.start();
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
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
                boolean displayAsHex = JdbcPlugin.isDisplayBinaryParameterAsHex(mirror.getSql(),
                        parameterIndex);
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
        public static Stopwatch onBefore(@InjectTarget Statement statement,
                @InjectMethodArg String sql) {

            StatementMirror mirror = getStatementMirror(statement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = new JdbcMessageSupplier(sql);
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startEntry(jdbcMessageSupplier, metric);
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
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            if (stopwatch != null) {
                stopwatch.stop();
            }
        }
    }

    // executeBatch is not included since it is handled separately (below)
    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "/execute|executeQuery"
            + "|executeUpdate/", captureNested = false, metricName = "jdbc execute")
    public static class PreparedStatementExecuteAdvice {
        private static final Metric metric = pluginServices.getMetric(
                PreparedStatementExecuteAdvice.class);
        @OnBefore
        public static Stopwatch onBefore(@InjectTarget PreparedStatement preparedStatement) {
            PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
            if (pluginServices.isEnabled()) {
                JdbcMessageSupplier jdbcMessageSupplier = new JdbcMessageSupplier(mirror.getSql(),
                        mirror.getParametersCopy());
                mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                return pluginServices.startEntry(jdbcMessageSupplier, metric);
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
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            if (stopwatch != null) {
                stopwatch.stop();
            }
        }
    }

    @Pointcut(typeName = "java.sql.Statement", methodName = "executeBatch", captureNested = false,
            metricName = "jdbc execute")
    public static class StatementExecuteBatchAdvice {
        private static final Metric metric = pluginServices.getMetric(
                StatementExecuteBatchAdvice.class);
        @OnBefore
        public static Stopwatch onBefore(@InjectTarget final Statement statement) {
            if (statement instanceof PreparedStatement) {
                PreparedStatementMirror mirror = getPreparedStatementMirror(
                        (PreparedStatement) statement);
                if (pluginServices.isEnabled()) {
                    JdbcMessageSupplier jdbcMessageSupplier = new JdbcMessageSupplier(
                            mirror.getSql(),
                            mirror.getBatchedParametersCopy());
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startEntry(jdbcMessageSupplier, metric);
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
                    JdbcMessageSupplier jdbcMessageSupplier = new JdbcMessageSupplier(
                            mirror.getBatchedSqlCopy());
                    // TODO track all changes to statement mirrors regardless of isEnabled
                    mirror.setLastJdbcMessageSupplier(jdbcMessageSupplier);
                    return pluginServices.startEntry(jdbcMessageSupplier, metric);
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
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            if (stopwatch != null) {
                stopwatch.stop();
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
            pluginServices.registerConfigurationListener(new ConfigurationListener() {
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    metricEnabled = pluginEnabled && pluginServices.getBooleanProperty(
                            "captureResultSetNext");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            metricEnabled = pluginEnabled && pluginServices.getBooleanProperty(
                    "captureResultSetNext");
        }
        @IsEnabled
        public static boolean isEnabled() {
            return pluginEnabled;
        }
        @OnBefore
        public static Stopwatch onBefore() {
            if (metricEnabled) {
                return metric.start();
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
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            if (stopwatch != null) {
                stopwatch.stop();
            }
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "/get.*/", methodArgs = { "int",
            ".." }, metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice.class);
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigurationListener(new ConfigurationListener() {
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled() && pluginServices
                            .getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled() && pluginServices.getBooleanProperty(
                    "captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled() {
            return metricEnabled;
        }
        @OnBefore
        public static Stopwatch onBefore() {
            return metric.start();
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
        }
    }

    @Pointcut(typeName = "java.sql.ResultSet", methodName = "/get.*/",
            methodArgs = { "java.lang.String", ".." },
            metricName = "jdbc resultset value")
    public static class ResultSetValueAdvice2 {
        private static final Metric metric = pluginServices.getMetric(ResultSetValueAdvice2.class);
        private static volatile boolean metricEnabled;
        static {
            pluginServices.registerConfigurationListener(new ConfigurationListener() {
                public void onChange() {
                    metricEnabled = pluginServices.isEnabled() && pluginServices
                            .getBooleanProperty("captureResultSetGet");
                }
            });
            metricEnabled = pluginServices.isEnabled() && pluginServices.getBooleanProperty(
                    "captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled() {
            return metricEnabled;
        }
        @OnBefore
        public static Stopwatch onBefore() {
            return metric.start();
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
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
        public static Stopwatch onBefore() {
            return pluginServices.startEntry(MessageSupplier.of("jdbc commit"), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
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
        public static Stopwatch onBefore() {
            return metric.start();
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
        }
    }

    // ================== Metadata ==================

    @Pointcut(typeName = "java.sql.DatabaseMetaData", methodName = "/.*/", methodArgs = { ".." },
            metricName = "jdbc metadata")
    public static class MetadataAdvice {
        private static final Metric metric = pluginServices.getMetric(MetadataAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Stopwatch onBefore() {
            return metric.start();
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Stopwatch stopwatch) {
            stopwatch.stop();
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
            if (!missingSqlTextErrorLogged.getAndSet(true)) {
                // this error is only logged the first time it occurs
                logger.error("SQL TEXT WAS NOT CAPTURED BY INFORMANT", new Throwable());
            }
            return new PreparedStatementMirror("SQL TEXT WAS NOT CAPTURED BY INFORMANT");
        } else {
            return mirror;
        }
    }
}
