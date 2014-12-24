/*
 * Copyright 2014 the original author or authors.
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

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

public class Slf4jMarkerAspect {

    private static final String TRACE_METRIC = "logging";

    private static final PluginServices pluginServices = PluginServices.get("logger");

    private static LogAdviceTraveler onBefore(FormattingTuple formattingTuple, String methodName,
            MetricName metricName) {
        String formattedMessage = nullToEmpty(formattingTuple.getMessage());
        Throwable throwable = formattingTuple.getThrowable();
        if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), throwable != null)) {
            pluginServices.setTransactionError(formattedMessage);
        }
        TraceEntry traceEntry = pluginServices.startTraceEntry(
                MessageSupplier.from("log {}: {}", methodName, formattedMessage),
                metricName);
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
            methodParameterTypes = {"org.slf4j.Marker", "java.lang.String"},
            metricName = TRACE_METRIC)
    public static class LogNoArgAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(LogNoArgAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice.get() && pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String message, @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            if (LoggerPlugin.markTraceAsError(methodName.equals("warn"), false)) {
                pluginServices.setTransactionError(message);
            }
            return pluginServices.startTraceEntry(
                    MessageSupplier.from("log {}: {}", methodName, message),
                    metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String message) {
            LoggerPlugin.inAdvice.set(false);
            traceEntry.endWithError(ErrorMessage.from(message));
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"org.slf4j.Marker", "java.lang.String", "java.lang.Object"},
            metricName = TRACE_METRIC)
    public static class LogOneArgAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(LogOneArgAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice.get() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String format, @BindParameter @Nullable Object arg,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg);
            return Slf4jMarkerAspect.onBefore(formattingTuple, methodName, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice.set(false);
            Slf4jMarkerAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"org.slf4j.Marker", "java.lang.String", "java.lang.Throwable"},
            metricName = TRACE_METRIC)
    public static class LogOneArgThrowableAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(LogOneArgThrowableAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice.get() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String format, @BindParameter @Nullable Object arg,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg);
            return Slf4jMarkerAspect.onBefore(formattingTuple, methodName, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice.set(false);
            Slf4jMarkerAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"org.slf4j.Marker", "java.lang.String", "java.lang.Object",
                    "java.lang.Object"}, metricName = TRACE_METRIC)
    public static class LogTwoArgsAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(LogTwoArgsAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice.get() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String format, @BindParameter @Nullable Object arg1,
                @BindParameter @Nullable Object arg2, @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            FormattingTuple formattingTuple = MessageFormatter.format(format, arg1, arg2);
            return Slf4jMarkerAspect.onBefore(formattingTuple, methodName, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice.set(false);
            Slf4jMarkerAspect.onAfter(traveler);
        }
    }

    @Pointcut(className = "org.slf4j.Logger", methodName = "warn|error",
            methodParameterTypes = {"org.slf4j.Marker", "java.lang.String", "java.lang.Object[]"},
            metricName = TRACE_METRIC)
    public static class LogAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(LogAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return !LoggerPlugin.inAdvice.get() && pluginServices.isEnabled();
        }
        @OnBefore
        public static LogAdviceTraveler onBefore(
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable String format,
                @BindParameter @Nullable Object/*@Nullable*/[] arguments,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            FormattingTuple formattingTuple = MessageFormatter.arrayFormat(format, arguments);
            return Slf4jMarkerAspect.onBefore(formattingTuple, methodName, metricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            LoggerPlugin.inAdvice.set(false);
            Slf4jMarkerAspect.onAfter(traveler);
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
