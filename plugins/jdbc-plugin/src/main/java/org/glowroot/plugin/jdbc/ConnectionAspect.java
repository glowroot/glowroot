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
import org.glowroot.api.MetricTimer;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindParameter;
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

    private static volatile boolean captureConnectionLifecycleSpans;
    private static volatile boolean captureTransactionLifecycleSpans;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureConnectionLifecycleSpans =
                        pluginServices.getBooleanProperty("captureConnectionLifecycleSpans");
                captureTransactionLifecycleSpans =
                        pluginServices.getBooleanProperty("captureTransactionLifecycleSpans");
            }
        });
        captureConnectionLifecycleSpans =
                pluginServices.getBooleanProperty("captureConnectionLifecycleSpans");
        captureTransactionLifecycleSpans =
                pluginServices.getBooleanProperty("captureTransactionLifecycleSpans");
    }

    @Pointcut(className = "java.sql.Connection", methodName = "commit", ignoreSelfNested = true,
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
            span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "rollback", ignoreSelfNested = true,
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
            span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "close", ignoreSelfNested = true,
            metricName = "jdbc connection close")
    public static class CloseAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionLifecycleSpans) {
                return pluginServices.startSpan(MessageSupplier.from("jdbc connection close"),
                        metricName);
            } else {
                return pluginServices.startMetric(metricName);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler Object spanOrTimer) {
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            } else {
                ((MetricTimer) spanOrTimer).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Object spanOrTimer) {
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithError(ErrorMessage.from(t));
            } else {
                ((MetricTimer) spanOrTimer).stop();
            }
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "setAutoCommit",
            ignoreSelfNested = true, methodParameterTypes = {"boolean"},
            metricName = "jdbc set autocommit")
    public static class SetAutoCommitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && captureTransactionLifecycleSpans;
        }
        @OnBefore
        public static Span onBefore(@BindParameter boolean autoCommit) {
            return pluginServices.startSpan(
                    MessageSupplier.from("jdbc set autocommit: {}", Boolean.toString(autoCommit)),
                    metricName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
    }
}
