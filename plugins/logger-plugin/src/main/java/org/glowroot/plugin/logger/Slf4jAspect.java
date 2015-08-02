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

import javax.annotation.Nullable;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import org.glowroot.plugin.api.ErrorMessage;
import org.glowroot.plugin.api.MessageSupplier;
import org.glowroot.plugin.api.PluginServices;
import org.glowroot.plugin.api.TimerName;
import org.glowroot.plugin.api.TraceEntry;
import org.glowroot.plugin.api.weaving.BindMethodName;
import org.glowroot.plugin.api.weaving.BindParameter;
import org.glowroot.plugin.api.weaving.BindTraveler;
import org.glowroot.plugin.api.weaving.IsEnabled;
import org.glowroot.plugin.api.weaving.OnAfter;
import org.glowroot.plugin.api.weaving.OnBefore;
import org.glowroot.plugin.api.weaving.Pointcut;

public class Slf4jAspect {

    private static final String TIMER_NAME = "logging";

    private static final PluginServices pluginServices = PluginServices.get("logger");

    private static LogAdviceTraveler onBefore(FormattingTuple formattingTuple, String methodName,
            TimerName timerName) {
        String formattedMessage = nullToEmpty(formattingTuple.getMessage());
        Throwable throwable = formattingTuple.getThrowable();
        if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), throwable != null)) {
            pluginServices.setTransactionError(ErrorMessage.from(formattedMessage, throwable));
        }
        TraceEntry traceEntry = pluginServices.startTraceEntry(
                MessageSupplier.from("log {}: {}", methodName, formattedMessage),
                timerName);
        return new LogAdviceTraveler(traceEntry, formattedMessage, throwable);
    }

    private static void onAfter(LogAdviceTraveler traveler) {
        Throwable t = traveler.throwable;
        if (t == null) {
            traveler.traceEntry.endWithError(ErrorMessage.from(traveler.formattedMessage));
        } else {
            traveler.traceEntry.endWithError(ErrorMessage.from(t.getMessage(), t));
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"java.lang.String"}, timerName = TIMER_NAME)
    public static class LogNoArgAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(LogNoArgAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter @Nullable String message,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), false)) {
                pluginServices.setTransactionError(ErrorMessage.from(message));
            }
            return pluginServices.startTraceEntry(
                    MessageSupplier.from("log {}: {}", methodName, message),
                    timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @BindParameter @Nullable String message) {
            LoggerPlugin.inAdvice(false);
            traceEntry.endWithError(ErrorMessage.from(message));
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"java.lang.String", "java.lang.Object"},
            timerName = TIMER_NAME)
    public static class LogOneArgAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(LogOneArgAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(@BindParameter @Nullable String format,
                @BindParameter @Nullable Object arg, @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg);
            return Slf4jAspect.onBefore(formattingTuple, methodName, timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Slf4jAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"java.lang.String", "java.lang.Throwable"},
            timerName = TIMER_NAME)
    public static class LogOneArgThrowableAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(LogOneArgThrowableAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(@BindParameter @Nullable String format,
                @BindParameter @Nullable Object arg, @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg);
            return Slf4jAspect.onBefore(formattingTuple, methodName, timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Slf4jAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"java.lang.String", "java.lang.Object", "java.lang.Object"},
            timerName = TIMER_NAME)
    public static class LogTwoArgsAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(LogTwoArgsAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(@BindParameter @Nullable String format,
                @BindParameter @Nullable Object arg1, @BindParameter @Nullable Object arg2,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg1, arg2);
            return Slf4jAspect.onBefore(formattingTuple, methodName, timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Slf4jAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"java.lang.String", "java.lang.Object[]"},
            timerName = TIMER_NAME)
    public static class LogAdvice {
        private static final TimerName timerName =
                pluginServices.getTimerName(LogAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(@BindParameter @Nullable String format,
                @BindParameter @Nullable Object/*@Nullable*/[] arguments,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice(true);
            FormattingTuple formattingTuple = MessageFormatter.arrayFormat(format, arguments);
            return Slf4jAspect.onBefore(formattingTuple, methodName, timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice(false);
            Slf4jAspect.onAfter(traveler);
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
