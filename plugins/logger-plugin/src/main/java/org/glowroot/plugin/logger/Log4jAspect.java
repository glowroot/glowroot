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
package org.glowroot.plugin.logger;

import java.util.Locale;

import javax.annotation.Nullable;

import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.config.ConfigService;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.BindMethodName;
import org.glowroot.plugin.api.weaving.BindParameter;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.Pointcut;

public class Log4jAspect {

    private static final String TIMER_NAME = "logging";

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("logger");

    @Pointcut(className = "org.apache.log4j.Category", methodName = "warn|error|fatal",
            methodParameterTypes = {"java.lang.Object"}, timerName = TIMER_NAME)
    public static class LogAdvice {
        private static final TimerName timerName = transactionService.getTimerName(LogAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && configService.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter @Nullable Object message,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            String messageText = String.valueOf(message);
            if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), false)) {
                transactionService.setTransactionError(messageText);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", methodName, messageText), timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @BindParameter @Nullable Object message) {
            LoggerPlugin.inAdvice(false);
            traceEntry.endWithError(String.valueOf(message));
        }
    }

    @Pointcut(className = "org.apache.log4j.Category", methodName = "warn|error|fatal",
            methodParameterTypes = {"java.lang.Object", "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LogWithThrowableAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LogWithThrowableAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && configService.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter @Nullable Object message,
                @BindParameter @Nullable Throwable t, @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            String messageText = String.valueOf(message);
            if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), t != null)) {
                transactionService.setTransactionError(messageText, t);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", methodName, messageText), timerName);
        }
        @OnAfter
        public static void onAfter(@BindParameter @Nullable Object message,
                @BindParameter @Nullable Throwable t, @BindTraveler TraceEntry traceEntry) {
            LoggerPlugin.inAdvice(false);
            if (t == null) {
                traceEntry.endWithError(String.valueOf(message));
            } else {
                // intentionally not passing message since it is already the trace entry message
                // and this way it will also capture/display Throwable's root cause message
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "org.apache.log4j.Category", methodName = "log",
            methodParameterTypes = {"org.apache.log4j.Priority", "java.lang.Object"},
            timerName = TIMER_NAME)
    public static class LogWithPriorityAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LogWithPriorityAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled(@BindParameter @Nullable Object priority) {
            if (priority == null) {
                // seems nothing sensible to do here other than ignore
                return false;
            }
            if (LoggerPlugin.inAdvice() || !configService.isEnabled()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object priority,
                @BindParameter @Nullable Object message) {
            LoggerPlugin.inAdvice(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            String messageText = String.valueOf(message);
            if (LoggerPlugin.markTraceAsError(level.equals("warn"), false)) {
                transactionService.setTransactionError(messageText);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", level, messageText), timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @BindParameter @Nullable Object message) {
            LoggerPlugin.inAdvice(false);
            traceEntry.endWithError(String.valueOf(message));
        }
    }

    @Pointcut(className = "org.apache.log4j.Category", methodName = "log",
            methodParameterTypes = {"org.apache.log4j.Priority", "java.lang.Object",
                    "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LogWithPriorityAndThrowableAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LogWithPriorityAndThrowableAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled(@BindParameter @Nullable Object priority) {
            if (priority == null) {
                // seems nothing sensible to do here other than ignore
                return false;
            }
            if (LoggerPlugin.inAdvice() || !configService.isEnabled()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object priority,
                @BindParameter @Nullable Object message, @BindParameter @Nullable Throwable t) {
            LoggerPlugin.inAdvice(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            String messageText = String.valueOf(message);
            if (LoggerPlugin.markTraceAsError(level.equals("warn"), t != null)) {
                transactionService.setTransactionError(messageText, t);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {}: {}", level, messageText), timerName);
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindParameter Object priority,
                @BindParameter @Nullable Object message, @BindParameter @Nullable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            LoggerPlugin.inAdvice(false);
            if (t == null) {
                traceEntry.endWithError(String.valueOf(message));
            } else {
                // intentionally not passing message since it is already the trace entry message
                // and this way it will also capture/display Throwable's root cause message
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "org.apache.log4j.Category", methodName = "l7dlog",
            methodParameterTypes = {"org.apache.log4j.Priority", "java.lang.String",
                    "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LocalizedLogAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LocalizedLogAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled(@BindParameter @Nullable Object priority) {
            if (priority == null) {
                // seems nothing sensible to do here other than ignore
                return false;
            }
            if (LoggerPlugin.inAdvice() || !configService.isEnabled()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object priority,
                @BindParameter @Nullable String key, @BindParameter @Nullable Throwable t) {
            LoggerPlugin.inAdvice(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (LoggerPlugin.markTraceAsError(level.equals("warn"), t != null)) {
                transactionService.setTransactionError(key, t);
            }
            return transactionService.startTraceEntry(
                    MessageSupplier.from("log {} (localized): {}", level, key), timerName);
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindParameter Object priority,
                @BindParameter @Nullable String key, @BindParameter @Nullable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            LoggerPlugin.inAdvice(false);
            if (t == null) {
                traceEntry.endWithError(key);
            } else {
                // intentionally not passing message since it is already the trace entry message
                // and this way it will also capture/display Throwable's root cause message
                traceEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "org.apache.log4j.Category",
            methodName = "l7dlog", methodParameterTypes = {"org.apache.log4j.Priority",
                    "java.lang.String", "java.lang.Object[]", "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LocalizedLogWithParametersAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(LocalizedLogWithParametersAdvice.class);
        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled(@BindParameter @Nullable Object priority) {
            if (priority == null) {
                // seems nothing sensible to do here other than ignore
                return false;
            }
            if (LoggerPlugin.inAdvice() || !configService.isEnabled()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object priority,
                @BindParameter @Nullable String key,
                @BindParameter @Nullable Object/*@Nullable*/[] params,
                @BindParameter @Nullable Throwable t) {
            LoggerPlugin.inAdvice(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (LoggerPlugin.markTraceAsError(level.equals("warn"), t != null)) {
                transactionService.setTransactionError(key, t);
            }
            if (params != null && params.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(params[i]);
                }
                return transactionService.startTraceEntry(MessageSupplier
                        .from("log {} (localized): {} [{}]", level, key, sb.toString()), timerName);
            } else {
                return transactionService.startTraceEntry(
                        MessageSupplier.from("log {} (localized): {}", level, key), timerName);
            }
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindParameter Object priority,
                @BindParameter @Nullable String key,
                @BindParameter @Nullable Object/*@Nullable*/[] params,
                @BindParameter @Nullable Throwable t, @BindTraveler TraceEntry traceEntry) {
            LoggerPlugin.inAdvice(false);
            if (t == null) {
                StringBuilder sb = new StringBuilder();
                sb.append(key);
                if (params != null && params.length > 0) {
                    sb.append(" [");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(params[i]);
                    }
                    sb.append("]");
                }
                traceEntry.endWithError(sb.toString());
            } else {
                // intentionally not passing message since it is already the trace entry message
                // and this way it will also capture/display Throwable's root cause message
                traceEntry.endWithError(t);
            }
        }
    }
}
