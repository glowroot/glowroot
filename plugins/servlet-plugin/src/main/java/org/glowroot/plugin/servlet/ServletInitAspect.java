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

import javax.annotation.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

public class ServletInitAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    /*
     * ================== Startup ==================
     */

    @Pointcut(className = "javax.servlet.ServletContextListener", methodName = "contextInitialized",
            methodParameterTypes = {"javax.servlet.ServletContextEvent"},
            metricName = "servlet startup")
    public static class ContextInitializedAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ContextInitializedAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return ServletPluginProperties.captureStartup() && pluginServices.isEnabled();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Object listener) {
            String transactionName =
                    "servlet context initialized / " + listener.getClass().getName();
            return pluginServices.startTransaction("Startup", transactionName,
                    MessageSupplier.from(listener.getClass().getName() + ".contextInitialized()"),
                    metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "javax.servlet.Servlet", methodName = "init",
            methodParameterTypes = {"javax.servlet.ServletConfig"}, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return ServletPluginProperties.captureStartup() && pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindReceiver Object servlet) {
            String transactionName = "servlet init / " + servlet.getClass().getName();
            return pluginServices.startTransaction("Startup", transactionName,
                    MessageSupplier.from(servlet.getClass().getName() + ".init()"),
                    metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "javax.servlet.Filter", methodName = "init",
            methodParameterTypes = {"javax.servlet.FilterConfig"}, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return ServletPluginProperties.captureStartup() && pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindReceiver Object filter) {
            String transactionName = "filter init / " + filter.getClass().getName();
            return pluginServices.startTransaction("Startup", transactionName,
                    MessageSupplier.from(filter.getClass().getName() + ".init()"), metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(ErrorMessage.from(t));
        }
    }
}
