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

public class Log4jAspect {

    private static final String TIMER_NAME = "logging";

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("logger");

    @Pointcut(className = "org.apache.log4j.Category", methodName = "forcedLog",
            methodParameterTypes = {"java.lang.String", "org.apache.log4j.Priority",
                    "java.lang.Object", "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class ForcedLogAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(ForcedLogAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && configService.isEnabled();
        }
        @OnBefore
        @SuppressWarnings("unused")
        public static TraceEntry onBefore(@BindParameter @Nullable String fqcn,
                @BindParameter @Nullable Object log4jLevel, @BindParameter @Nullable Object message,
                @BindParameter @Nullable Throwable t) {
            LoggerPlugin.inAdvice(true);
            String messageText = String.valueOf(message);
            Level level = getLevel(log4jLevel);
            if (LoggerPlugin.markTraceAsError(level, t != null)) {
                transactionService.setTransactionError(messageText);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", level.getName(), messageText), timerName);
        }
        @OnAfter
        @SuppressWarnings("unused")
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @BindParameter @Nullable String fqcn, @BindParameter @Nullable Object priority,
                @BindParameter @Nullable Object message, @BindParameter @Nullable Throwable t) {
            LoggerPlugin.inAdvice(false);
            if (t == null) {
                traceEntry.endWithError(String.valueOf(message));
            } else {
                // intentionally not passing message since it is already the trace entry message
                traceEntry.endWithError(t);
            }
        }
    }

    private static Level getLevel(@Nullable Object log4jLevel) {
        if (log4jLevel == null) {
            return Level.UNKNOWN;
        }
        String log4jLevelStr = log4jLevel.toString();
        if ("DEBUG".equals(log4jLevelStr)) {
            return Level.DEBUG;
        } else if ("INFO".equals(log4jLevelStr)) {
            return Level.INFO;
        } else if ("WARN".equals(log4jLevelStr)) {
            return Level.WARN;
        } else if ("ERROR".equals(log4jLevelStr)) {
            return Level.ERROR;
        } else if ("FATAL".equals(log4jLevelStr)) {
            return Level.FATAL;
        } else {
            return Level.UNKNOWN;
        }
    }
}
