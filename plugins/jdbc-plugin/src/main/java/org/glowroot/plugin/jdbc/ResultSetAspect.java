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

import org.glowroot.plugin.api.Logger;
import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.config.BooleanProperty;
import org.glowroot.plugin.api.config.ConfigService;
import org.glowroot.plugin.api.transaction.QueryEntry;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.BindReceiver;
import org.glowroot.plugin.api.weaving.BindReturn;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.OnReturn;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.StatementAspect.HasStatementMirror;

public class ResultSetAspect {

    private static final Logger logger = Agent.getLogger(ResultSetAspect.class);
    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("jdbc");

    @Pointcut(className = "java.sql.ResultSet", methodName = "next", methodParameterTypes = {},
            timerName = "jdbc resultset navigate")
    public static class NextAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(NextAdvice.class);
        private static final BooleanProperty timerEnabled =
                configService.getEnabledProperty("captureResultSetNavigate");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return resultSet.glowroot$hasStatementMirror() && configService.isEnabled();
        }
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver HasStatementMirror resultSet) {
            if (timerEnabled.value()) {
                return onBeforeCommon(resultSet, timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn boolean currentRowValid,
                @BindReceiver HasStatementMirror resultSet) {
            StatementMirror mirror = resultSet.glowroot$getStatementMirror();
            if (mirror == null) {
                // this shouldn't happen since just checked above in isEnabled(), unless some
                // bizarre concurrent mis-usage of ResultSet
                return;
            }
            QueryEntry lastQueryEntry = mirror.getLastQueryEntry();
            if (lastQueryEntry == null) {
                // tracing must be disabled (e.g. exceeded trace entry limit)
                return;
            }
            if (currentRowValid) {
                // ResultSet.getRow() is sometimes not super duper fast due to ResultSet
                // wrapping and other checks, so this optimizes the common case
                lastQueryEntry.incrementCurrRow();
            } else {
                lastQueryEntry.setCurrRow(0);
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "java.sql.ResultSet",
            methodName = "previous|relative|absolute|first|last", methodParameterTypes = "..",
            timerName = "jdbc resultset navigate")
    public static class NavigateAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(NavigateAdvice.class);
        private static final BooleanProperty timerEnabled =
                configService.getEnabledProperty("captureResultSetNavigate");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return resultSet.glowroot$hasStatementMirror() && configService.isEnabled();
        }
        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver HasStatementMirror resultSet) {
            if (timerEnabled.value()) {
                return onBeforeCommon(resultSet, timerName);
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindReceiver HasStatementMirror resultSet) {
            try {
                StatementMirror mirror = resultSet.glowroot$getStatementMirror();
                if (mirror == null) {
                    // this shouldn't happen since just checked above in isEnabled(), unless some
                    // bizarre concurrent mis-usage of ResultSet
                    return;
                }
                QueryEntry lastQueryEntry = mirror.getLastQueryEntry();
                if (lastQueryEntry == null) {
                    // tracing must be disabled (e.g. exceeded trace entry limit)
                    return;
                }
                lastQueryEntry.setCurrRow(((ResultSet) resultSet).getRow());
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
                transactionService.getTimerName(ValueAdvice.class);
        private static final BooleanProperty timerEnabled =
                configService.getEnabledProperty("captureResultSetGet");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return timerEnabled.value() && resultSet.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static Timer onBefore(@BindReceiver HasStatementMirror resultSet) {
            return onBeforeCommon(resultSet, timerName);
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
                transactionService.getTimerName(ValueAdvice2.class);
        private static final BooleanProperty timerEnabled =
                configService.getEnabledProperty("captureResultSetGet");
        @IsEnabled
        public static boolean isEnabled(@BindReceiver HasStatementMirror resultSet) {
            // don't capture if implementation detail of a DatabaseMetaData method
            return timerEnabled.value() && resultSet.glowroot$hasStatementMirror();
        }
        @OnBefore
        public static Timer onBefore(@BindReceiver HasStatementMirror resultSet) {
            return onBeforeCommon(resultSet, timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
        }
    }

    private static Timer onBeforeCommon(HasStatementMirror resultSet, TimerName timerName) {
        StatementMirror mirror = resultSet.glowroot$getStatementMirror();
        if (mirror == null) {
            // this shouldn't happen since just checked above in isEnabled(), unless some
            // bizarre concurrent mis-usage of ResultSet
            return transactionService.startTimer(timerName);
        }
        QueryEntry lastQueryEntry = mirror.getLastQueryEntry();
        if (lastQueryEntry == null) {
            // tracing must be disabled (e.g. exceeded trace entry limit)
            return transactionService.startTimer(timerName);
        }
        return lastQueryEntry.extend();
    }
}
