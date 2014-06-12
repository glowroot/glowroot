/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ServletInitAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    /*
     * ================== Startup ==================
     */

    @Pointcut(type = "javax.servlet.ServletContextListener", methodName = "contextInitialized",
            methodArgTypes = {"javax.servlet.ServletContextEvent"}, traceMetric = "servlet startup")
    public static class ContextInitializedAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(ContextInitializedAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindReceiver Object listener) {
            String transactionName =
                    "servlet context initialized / " + listener.getClass().getName();
            return pluginServices.startBackgroundTrace(transactionName,
                    MessageSupplier.from(listener.getClass().getName() + ".contextInitialized()"),
                    traceMetricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(type = "javax.servlet.Servlet", methodName = "init",
            methodArgTypes = {"javax.servlet.ServletConfig"}, traceMetric = "servlet startup")
    public static class ServletInitAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object servlet) {
            String transactionName = "servlet init / " + servlet.getClass().getName();
            return pluginServices
                    .startBackgroundTrace(transactionName,
                            MessageSupplier.from(servlet.getClass().getName() + ".init()"),
                            traceMetricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(type = "javax.servlet.Filter", methodName = "init",
            methodArgTypes = {"javax.servlet.FilterConfig"}, traceMetric = "servlet startup")
    public static class FilterInitAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindReceiver Object filter) {
            String transactionName = "filter init / " + filter.getClass().getName();
            return pluginServices.startBackgroundTrace(transactionName,
                    MessageSupplier.from(filter.getClass().getName() + ".init()"), traceMetricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }
}
