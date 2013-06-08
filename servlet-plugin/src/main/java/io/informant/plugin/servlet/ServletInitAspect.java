/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.servlet;

import checkers.nullness.quals.Nullable;

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ServletInitAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:servlet-plugin");

    /*
     * ================== Startup ==================
     */

    @Pointcut(typeName = "javax.servlet.ServletContextListener", methodName = "contextInitialized",
            methodArgs = {"javax.servlet.ServletContextEvent"}, metricName = "servlet startup")
    public static class ContextInitializedAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ContextInitializedAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindTarget Object listener) {
            return pluginServices.startBackgroundTrace(MessageSupplier.from(
                    "servlet context initialized ({})", listener.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "init",
            methodArgs = {"javax.servlet.ServletConfig"}, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindTarget Object servlet) {
            return pluginServices.startBackgroundTrace(MessageSupplier.from("servlet init ({})",
                    servlet.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "init",
            methodArgs = {"javax.servlet.FilterConfig"}, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindTarget Object filter) {
            return pluginServices.startBackgroundTrace(MessageSupplier.from("filter init ({})",
                    filter.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }
}
