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
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.logger.LoggerPlugin.Level;

public class LogbackAspect {

    private static final String TIMER_NAME = "logging";

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("logger");

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
        public static LogAdviceTraveler onBefore(@BindParameter @Nullable String fqcn,
                @BindParameter @Nullable Object marker,
                @BindParameter @Nullable Object logbackLevel,
                @BindParameter @Nullable String message,
                @BindParameter @Nullable Object/*@Nullable*/[] params,
                @BindParameter @Nullable Throwable throwable) {
            LoggerPlugin.inAdvice(true);
            Level level = getLevel(logbackLevel);
            FormattingTuple formattingTuple = MessageFormatter.arrayFormat(message, params);
            Throwable t = throwable == null ? formattingTuple.getThrowable() : throwable;
            String formattedMessage = nullToEmpty(formattingTuple.getMessage());
            if (LoggerPlugin.markTraceAsError(level, t != null)) {
                transactionService.setTransactionError(formattedMessage);
            }
            TraceEntry traceEntry = transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", level.getName(), formattedMessage),
                    timerName);
            return new LogAdviceTraveler(traceEntry, formattedMessage, t);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Throwable t = traveler.throwable;
            if (t == null) {
                traveler.traceEntry.endWithError(traveler.formattedMessage);
            } else {
                // intentionally not passing message since it is already the trace entry message
                traveler.traceEntry.endWithError(t);
            }
        }
    }

    private static Level getLevel(@Nullable Object logbackLevel) {
        if (logbackLevel == null) {
            return Level.UNKNOWN;
        }
        String logbackLevelStr = logbackLevel.toString();
        if ("TRACE".equals(logbackLevelStr)) {
            return Level.TRACE;
        } else if ("DEBUG".equals(logbackLevelStr)) {
            return Level.DEBUG;
        } else if ("INFO".equals(logbackLevelStr)) {
            return Level.INFO;
        } else if ("WARN".equals(logbackLevelStr)) {
            return Level.WARN;
        } else if ("ERROR".equals(logbackLevelStr)) {
            return Level.ERROR;
        } else {
            return Level.UNKNOWN;
        }
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static class LogAdviceTraveler {
        private final TraceEntry traceEntry;
        private final String formattedMessage;
        private final @Nullable Throwable throwable;
        private LogAdviceTraveler(TraceEntry traceEntry, String formattedMessage,
                @Nullable Throwable throwable) {
            this.traceEntry = traceEntry;
            this.formattedMessage = formattedMessage;
            this.throwable = throwable;
        }
    }
}
