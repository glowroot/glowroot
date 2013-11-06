/*
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

import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import checkers.nullness.quals.Nullable;

import io.informant.api.ErrorMessage;
import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.api.weaving.BindMethodArg;
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// many of the pointcuts are not restricted to pluginServices.isEnabled() because StatementMirrors
// must be tracked for their entire life
public class StatementAspect {

    private static final Logger logger = LoggerFactory.getLogger(StatementAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

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
            methodArgs = {"java.lang.String", ".."}, captureNested = false,
            metricName = "jdbc prepare")
    public static class PrepareAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(PrepareAdvice.class);
        @OnBefore
        @Nullable
        public static MetricTimer onBefore() {
            // don't capture if implementation detail of a DatabaseMetaData method
            // (can't use @IsEnabled since need @OnReturn to always execute)
            if (pluginServices.isEnabled()
                    && !DatabaseMetaDataAspect.isCurrentlyExecuting()) {
                return pluginServices.startMetricTimer(metricName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn PreparedStatement preparedStatement,
                @BindMethodArg String sql) {
            ((HasStatementMirror) preparedStatement)
                    .setInformantStatementMirror(new PreparedStatementMirror(sql));
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable MetricTimer metricTimer) {
            if (metricTimer != null) {
                metricTimer.stop();
            }
        }
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case below to handle setNull()
    @Pointcut(typeName = "java.sql.PreparedStatement", methodName = "/(?!setNull$)set.*/",
            methodArgs = {"int", "*", ".."}, captureNested = false)
    public static class SetXAdvice {
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
    public static class SetNullAdvice {
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
    public static class ClearBatchAdvice {
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
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
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
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
                // clear lastJdbcMessageSupplier so that its numRows won't be updated if the plugin
                // is re-enabled in the middle of iterating over a different result set
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
            return !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
                    // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                    // plugin is re-enabled in the middle of iterating over a different result set
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
                    // clear lastJdbcMessageSupplier so that its numRows won't be updated if the
                    // plugin is re-enabled in the middle of iterating over a different result set
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

    // ================== Statement Closing ==================

    @Pointcut(typeName = "java.sql.Statement", methodName = "close", captureNested = false,
            metricName = "jdbc statement close")
    public static class CloseAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // don't capture if implementation detail of a DatabaseMetaData method
            return pluginServices.isEnabled()
                    && !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
            String databaseMetaDataMethodName =
                    DatabaseMetaDataAspect.getCurrentlyExecutingMethodName();
            if (databaseMetaDataMethodName != null) {
                // wrapping description in sql comment (/* */)
                mirror = new PreparedStatementMirror("/* internal prepared statement generated by"
                        + " java.sql.DatabaseMetaData." + databaseMetaDataMethodName + "() */");
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
}
