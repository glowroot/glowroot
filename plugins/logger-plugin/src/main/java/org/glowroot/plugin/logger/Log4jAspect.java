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

import java.util.Locale;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.BindMethodArg;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Log4jAspect {

    private static final String METRIC_NAME = "logging";

    private static final PluginServices pluginServices = PluginServices.get("logger");

    private static boolean markTraceAsError(boolean warn, boolean throwable) {
        boolean traceErrorOnErrorWithNoThrowable =
                pluginServices.getBooleanProperty("traceErrorOnErrorWithNoThrowable");
        boolean traceErrorOnWarn = pluginServices.getBooleanProperty("traceErrorOnWarn");
        return (!warn || traceErrorOnWarn) && (throwable || traceErrorOnErrorWithNoThrowable);
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "warn|error|fatal",
            methodArgTypes = {"java.lang.Object"}, traceMetric = METRIC_NAME)
    public static class LogAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && !LoggerPlugin.inAdvice.get();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object message,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            if (markTraceAsError(methodName.equals("warn"), false)) {
                pluginServices.setTraceError(String.valueOf(message));
            }
            return pluginServices.startSpan(
                    MessageSupplier.from("log {}: {}", methodName, String.valueOf(message)),
                    traceMetricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span, @BindMethodArg Object message) {
            LoggerPlugin.inAdvice.set(false);
            span.endWithError(ErrorMessage.from(String.valueOf(message)));
        }
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "warn|error|fatal",
            methodArgTypes = {"java.lang.Object", "java.lang.Throwable"}, traceMetric = METRIC_NAME)
    public static class LogWithThrowableAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogWithThrowableAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && !LoggerPlugin.inAdvice.get();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object message, @BindMethodArg Throwable t,
                @BindMethodName String methodName) {
            LoggerPlugin.inAdvice.set(true);
            if (markTraceAsError(methodName.equals("warn"), t != null)) {
                pluginServices.setTraceError(String.valueOf(message));
            }
            return pluginServices.startSpan(
                    MessageSupplier.from("log {}: {}", methodName, String.valueOf(message)),
                    traceMetricName);
        }
        @OnAfter
        public static void onAfter(@BindMethodArg Object message, @BindMethodArg Throwable t,
                @BindTraveler Span span) {
            LoggerPlugin.inAdvice.set(false);
            if (t == null) {
                span.endWithError(ErrorMessage.from(String.valueOf(message)));
            } else {
                span.endWithError(ErrorMessage.from(t.getMessage(), t));
            }
        }
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "log",
            methodArgTypes = {"org.apache.log4j.Priority", "java.lang.Object"},
            traceMetric = METRIC_NAME)
    public static class LogWithPriorityAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogWithPriorityAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg Object priority) {
            if (!pluginServices.isEnabled() || LoggerPlugin.inAdvice.get()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object priority, @BindMethodArg Object message) {
            LoggerPlugin.inAdvice.set(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (markTraceAsError(level.equals("warn"), false)) {
                pluginServices.setTraceError(String.valueOf(message));
            }
            return pluginServices.startSpan(
                    MessageSupplier.from("log {}: {}", level, String.valueOf(message)),
                    traceMetricName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Span span, @BindMethodArg Object message) {
            LoggerPlugin.inAdvice.set(false);
            span.endWithError(ErrorMessage.from(String.valueOf(message)));
        }
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "log",
            methodArgTypes = {"org.apache.log4j.Priority", "java.lang.Object",
                    "java.lang.Throwable"}, traceMetric = METRIC_NAME)
    public static class LogWithPriorityAndThrowableAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogWithPriorityAndThrowableAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg Object priority) {
            if (!pluginServices.isEnabled() || LoggerPlugin.inAdvice.get()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object priority, @BindMethodArg Object message,
                @BindMethodArg Throwable t) {
            LoggerPlugin.inAdvice.set(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (markTraceAsError(level.equals("warn"), t != null)) {
                pluginServices.setTraceError(String.valueOf(message));
            }
            return pluginServices.startSpan(
                    MessageSupplier.from("log {}: {}", level, String.valueOf(message)),
                    traceMetricName);
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindMethodArg Object priority,
                @BindMethodArg Object message, @BindMethodArg Throwable t,
                @BindTraveler Span span) {
            LoggerPlugin.inAdvice.set(false);
            if (t == null) {
                span.endWithError(ErrorMessage.from(String.valueOf(message)));
            } else {
                span.endWithError(ErrorMessage.from(t.getMessage(), t));
            }
        }
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "l7dlog",
            methodArgTypes = {"org.apache.log4j.Priority", "java.lang.String",
                    "java.lang.Throwable"}, traceMetric = METRIC_NAME)
    public static class LocalizedLogAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LocalizedLogAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg Object priority) {
            if (!pluginServices.isEnabled() || LoggerPlugin.inAdvice.get()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object priority, @BindMethodArg String key,
                @BindMethodArg Throwable t) {
            LoggerPlugin.inAdvice.set(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (markTraceAsError(level.equals("warn"), t != null)) {
                pluginServices.setTraceError(key);
            }
            return pluginServices.startSpan(
                    MessageSupplier.from("log {} (localized): {}", level, key), traceMetricName);
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindMethodArg Object priority,
                @BindMethodArg String key, @BindMethodArg Throwable t, @BindTraveler Span span) {
            LoggerPlugin.inAdvice.set(false);
            if (t == null) {
                span.endWithError(ErrorMessage.from(key));
            } else {
                span.endWithError(ErrorMessage.from(t.getMessage(), t));
            }
        }
    }

    @Pointcut(type = "org.apache.log4j.Category", methodName = "l7dlog",
            methodArgTypes = {"org.apache.log4j.Priority", "java.lang.String",
                    "java.lang.Object[]", "java.lang.Throwable"}, traceMetric = METRIC_NAME)
    public static class LocalizedLogWithParametersAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LocalizedLogWithParametersAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg Object priority) {
            if (!pluginServices.isEnabled() || LoggerPlugin.inAdvice.get()) {
                return false;
            }
            String level = priority.toString();
            return level.equals("FATAL") || level.equals("ERROR") || level.equals("WARN");
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object priority, @BindMethodArg String key,
                @BindMethodArg Object[] params, @BindMethodArg Throwable t) {
            LoggerPlugin.inAdvice.set(true);
            String level = priority.toString().toLowerCase(Locale.ENGLISH);
            if (markTraceAsError(level.equals("warn"), t != null)) {
                pluginServices.setTraceError(key);
            }
            if (params.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(params[i]);
                }
                return pluginServices.startSpan(
                        MessageSupplier.from("log {} (localized): {} [{}]", level, key,
                                sb.toString()), traceMetricName);
            } else {
                return pluginServices.startSpan(
                        MessageSupplier.from("log {} (localized): {}", level, key),
                        traceMetricName);
            }
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindMethodArg Object priority,
                @BindMethodArg String key, @BindMethodArg Object[] params,
                @BindMethodArg Throwable t, @BindTraveler Span span) {
            LoggerPlugin.inAdvice.set(false);
            StringBuilder sb = new StringBuilder();
            sb.append(key);
            if (params.length > 0) {
                sb.append(" [");
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(params[i]);
                }
                sb.append("]");
            }
            if (t == null) {
                span.endWithError(ErrorMessage.from(sb.toString()));
            } else {
                span.endWithError(ErrorMessage.from(t.getMessage(), t));
            }
        }
    }
}
