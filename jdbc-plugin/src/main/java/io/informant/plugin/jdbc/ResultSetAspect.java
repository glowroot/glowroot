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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import checkers.nullness.quals.Nullable;

import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.Pointcut;
import io.informant.plugin.jdbc.StatementAspect.HasStatementMirror;
import io.informant.shaded.slf4j.Logger;
import io.informant.shaded.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ResultSetAspect {

    private static final Logger logger = LoggerFactory.getLogger(ResultSetAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    @Pointcut(typeName = "java.sql.ResultSet",
            methodName = "next|previous|relative|absolute|first|last", methodArgs = "..",
            captureNested = false, metricName = "jdbc resultset navigate")
    public static class NavigateAdvice {
        private static final MetricName metricName = pluginServices
                .getMetricName(NavigateAdvice.class);
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
            return pluginEnabled && !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
                    lastJdbcMessageSupplier.updateNumRows(resultSet.getRow());
                } else {
                    lastJdbcMessageSupplier.setHasPerformedNavigation();
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
    public static class ValueAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ValueAdvice.class);
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
            return metricEnabled && !DatabaseMetaDataAspect.isCurrentlyExecuting();
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
    public static class ValueAdvice2 {
        private static final MetricName metricName =
                pluginServices.getMetricName(ValueAdvice2.class);
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
            return metricEnabled && !DatabaseMetaDataAspect.isCurrentlyExecuting();
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

    private static StatementMirror getStatementMirror(Statement statement) {
        StatementMirror mirror = ((HasStatementMirror) statement).getInformantStatementMirror();
        if (mirror == null) {
            mirror = new StatementMirror();
            ((HasStatementMirror) statement).setInformantStatementMirror(mirror);
        }
        return mirror;
    }
}
