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

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.BindParameter;
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
public class CommonsLoggingAspect {

    private static final String TRACE_METRIC = "logging";

    private static final PluginServices pluginServices = PluginServices.get("logger");

    private static boolean markTraceAsError(boolean warn, boolean throwable) {
        boolean traceErrorOnErrorWithNoThrowable =
                pluginServices.getBooleanProperty("traceErrorOnErrorWithNoThrowable");
        boolean traceErrorOnWarn = pluginServices.getBooleanProperty("traceErrorOnWarn");
        return (!warn || traceErrorOnWarn) && (throwable || traceErrorOnErrorWithNoThrowable);
    }

    @Pointcut(className = "org.apache.commons.logging.Log", methodName = "warn|error|fatal",
            methodParameterTypes = {"java.lang.Object"}, traceMetric = TRACE_METRIC)
    public static class LogAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && !LoggerPlugin.inAdvice.get();
        }
        @OnBefore
        public static Span onBefore(@BindParameter Object message,
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
        public static void onAfter(@BindTraveler Span span, @BindParameter Object message) {
            LoggerPlugin.inAdvice.set(false);
            span.endWithError(ErrorMessage.from(String.valueOf(message)));
        }
    }

    @Pointcut(className = "org.apache.commons.logging.Log", methodName = "warn|error|fatal",
            methodParameterTypes = {"java.lang.Object", "java.lang.Throwable"},
            traceMetric = TRACE_METRIC)
    public static class LogWithThrowableAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(LogWithThrowableAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && !LoggerPlugin.inAdvice.get();
        }
        @OnBefore
        public static Span onBefore(@BindParameter Object message, @BindParameter Throwable t,
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
        public static void onAfter(@BindParameter Object message, @BindParameter Throwable t,
                @BindTraveler Span span) {
            LoggerPlugin.inAdvice.set(false);
            if (t == null) {
                span.endWithError(ErrorMessage.from(String.valueOf(message)));
            } else {
                span.endWithError(ErrorMessage.from(t.getMessage(), t));
            }
        }
    }
}
