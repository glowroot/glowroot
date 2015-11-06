/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.agent.plugin.logger;

import javax.annotation.Nullable;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class LogbackAspect {

    private static final String TIMER_NAME = "logging";

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("logger");

    // constants from from ch.qos.logback.classic.Level
    private static final int OFF_INT = Integer.MAX_VALUE;
    private static final int ERROR_INT = 40000;
    private static final int WARN_INT = 30000;
    private static final int INFO_INT = 20000;
    private static final int DEBUG_INT = 10000;
    private static final int TRACE_INT = 5000;
    private static final int ALL_INT = Integer.MIN_VALUE;

    @Shim("org.slf4j.Logger")
    public interface Logger {
        String getName();
    }

    @Shim("ch.qos.logback.classic.Level")
    public interface Level {
        int toInt();
    }

    @Pointcut(className = "ch.qos.logback.classic.Logger",
            methodName = "buildLoggingEventAndAppend",
            methodParameterTypes = {"java.lang.String", "org.slf4j.Marker",
                    "ch.qos.logback.classic.Level", "java.lang.String", "java.lang.Object[]",
                    "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LogNoArgAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LogNoArgAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && configService.isEnabled();
        }
        @OnBefore
        @SuppressWarnings("unused")
        public static LogAdviceTraveler onBefore(@BindReceiver Logger logger,
                @BindParameter @Nullable String fqcn,
                @BindParameter @Nullable Object marker,
                @BindParameter @Nullable Level level,
                @BindParameter @Nullable String message,
                @BindParameter @Nullable Object/*@Nullable*/[] params,
                @BindParameter @Nullable Throwable throwable) {
            LoggerPlugin.inAdvice(true);
            FormattingTuple formattingTuple = MessageFormatter.arrayFormat(message, params);
            Throwable t = throwable == null ? formattingTuple.getThrowable() : throwable;
            String formattedMessage = nullToEmpty(formattingTuple.getMessage());
            int lvl = level == null ? 0 : level.toInt();
            if (LoggerPlugin.markTraceAsError(lvl >= ERROR_INT, lvl >= WARN_INT, t != null)) {
                transactionService.setTransactionError(formattedMessage);
            }
            TraceEntry traceEntry;
            if (lvl <= DEBUG_INT) {
                // include short logger name for debug or lower
                String loggerName = LoggerPlugin.getShortName(logger.getName());
                traceEntry = transactionService.startTraceEntry(
                        MessageSupplier.from("log {}: {} - {}", getLevelStr(lvl), loggerName,
                                formattedMessage),
                        timerName);
            } else {
                traceEntry = transactionService.startTraceEntry(
                        MessageSupplier.from("log {}: {}", getLevelStr(lvl), formattedMessage),
                        timerName);
            }
            return new LogAdviceTraveler(traceEntry, lvl, formattedMessage, t);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Throwable t = traveler.throwable;
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                traveler.traceEntry.endWithError(t);
            } else if (traveler.level >= WARN_INT) {
                traveler.traceEntry.endWithError(traveler.formattedMessage);
            } else {
                traveler.traceEntry.end();
            }
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String getLevelStr(int lvl) {
        switch (lvl) {
            case ALL_INT:
                return "all";
            case TRACE_INT:
                return "trace";
            case DEBUG_INT:
                return "debug";
            case INFO_INT:
                return "info";
            case WARN_INT:
                return "warn";
            case ERROR_INT:
                return "error";
            case OFF_INT:
                return "off";
            default:
                return "unknown (" + lvl + ")";
        }
    }

    private static class LogAdviceTraveler {

        private final TraceEntry traceEntry;
        private final int level;
        private final String formattedMessage;
        private final @Nullable Throwable throwable;

        private LogAdviceTraveler(TraceEntry traceEntry, int level, String formattedMessage,
                @Nullable Throwable throwable) {
            this.traceEntry = traceEntry;
            this.level = level;
            this.formattedMessage = formattedMessage;
            this.throwable = throwable;
        }
    }
}
