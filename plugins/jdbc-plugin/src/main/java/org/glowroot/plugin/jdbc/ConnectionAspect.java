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
package org.glowroot.plugin.jdbc;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConnectionAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static volatile int stackTraceThresholdMillis;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
        Double value = pluginServices.getDoubleProperty("stackTraceThresholdMillis");
        stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "commit", ignoreSameNested = true,
            metricName = "jdbc commit")
    public static class CommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            return pluginServices.startSpan(MessageSupplier.from("jdbc commit"), metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(typeName = "java.sql.Connection", methodName = "rollback", ignoreSameNested = true,
            metricName = "jdbc rollback")
    public static class RollbackAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(RollbackAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            return pluginServices.startSpan(MessageSupplier.from("jdbc rollback"), metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.endWithStackTrace(stackTraceThresholdMillis, MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }
}
