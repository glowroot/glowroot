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

import java.sql.ResultSet;
import java.sql.SQLException;

import javax.annotation.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.StatementAspect.HasStatementMirror;
import org.glowroot.plugin.jdbc.message.JdbcMessageSupplier;

public class ResultSetAspect {

    private static final Logger logger = LoggerFactory.getLogger(ResultSetAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    @Pointcut(className = "java.sql.ResultSet",
            methodName = "next|previous|relative|absolute|first|last", methodParameterTypes = "..",
            timerName = "jdbc resultset navigate")
    public static class NavigateAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(NavigateAdvice.class);
        private static volatile boolean pluginEnabled;
        // plugin configuration property captureResultSetNavigate is cached to limit map lookups
        private static volatile boolean timerEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    timerEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("captureResultSetNavigate");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            timerEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureResultSetNavigate");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return resultSet.hasGlowrootStatementMirror() && pluginEnabled;
        }
        @OnBefore
        public static @Nullable Timer onBefore() {
            if (timerEnabled) {
                return pluginServices.startTimer(timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn boolean currentRowValid,
                @BindReceiver HasStatementMirror resultSet, @BindMethodName String methodName) {
            try {
                StatementMirror mirror = resultSet.getGlowrootStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked above in isEnabled(), unless some
                    // bizarre concurrent mis-usage of ResultSet
                    return;
                }
                JdbcMessageSupplier lastJdbcMessageSupplier = mirror.getLastJdbcMessageSupplier();
                if (lastJdbcMessageSupplier == null) {
                    // tracing must be disabled (e.g. exceeded trace entry limit)
                    return;
                }
                if (currentRowValid) {
                    if (methodName.equals("next")) {
                        // ResultSet.getRow() is sometimes not super duper fast due to ResultSet
                        // wrapping and other checks, so this optimizes the common case
                        lastJdbcMessageSupplier.incrementNumRows();
                    } else {
                        lastJdbcMessageSupplier.updateNumRows(((ResultSet) resultSet).getRow());
                    }
                } else {
                    lastJdbcMessageSupplier.setHasPerformedNavigation();
                }
            } catch (SQLException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "java.sql.ResultSet", methodName = "get*",
            methodParameterTypes = {"int", ".."}, timerName = "jdbc resultset value")
    public static class ValueAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(ValueAdvice.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean timerEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    timerEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            timerEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return timerEnabled && resultSet.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    @Pointcut(className = "java.sql.ResultSet", methodName = "get*",
            methodParameterTypes = {"java.lang.String", ".."}, timerName = "jdbc resultset value")
    public static class ValueAdvice2 {
        private static final TimerName timerName =
                pluginServices.getTimerName(ValueAdvice2.class);
        // plugin configuration property captureResultSetGet is cached to limit map lookups
        private static volatile boolean timerEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    timerEnabled = pluginServices.isEnabled()
                            && pluginServices.getBooleanProperty("captureResultSetGet");
                }
            });
            timerEnabled = pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty("captureResultSetGet");
        }
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return timerEnabled && resultSet.hasGlowrootStatementMirror();
        }
        @OnBefore
        public static Timer onBefore() {
            return pluginServices.startTimer(timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }
}
