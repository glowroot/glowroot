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
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.TraceMetricTimer;
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

    private static volatile boolean captureConnectionCloseSpans;
    private static volatile boolean captureSetAutoCommitSpans;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureConnectionCloseSpans =
                        pluginServices.getBooleanProperty("captureConnectionCloseSpans");
                captureSetAutoCommitSpans =
                        pluginServices.getBooleanProperty("captureSetAutoCommitSpans");
            }
        });
        captureConnectionCloseSpans =
                pluginServices.getBooleanProperty("captureConnectionCloseSpans");
        captureSetAutoCommitSpans = pluginServices.getBooleanProperty("captureSetAutoCommitSpans");
    }

    @Pointcut(className = "java.sql.Connection", methodName = "commit", ignoreSelfNested = true,
            traceMetric = "jdbc commit")
    public static class CommitAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            return pluginServices.startSpan(MessageSupplier.from("jdbc commit"), traceMetricName);
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
            traceMetric = "jdbc rollback")
    public static class RollbackAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(RollbackAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore() {
            return pluginServices.startSpan(MessageSupplier.from("jdbc rollback"), traceMetricName);
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
            traceMetric = "jdbc connection close")
    public static class CloseAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureConnectionCloseSpans) {
                return pluginServices.startSpan(MessageSupplier.from("jdbc connection close"),
                        traceMetricName);
            } else {
                return pluginServices.startTraceMetric(traceMetricName);
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler Object spanOrTimer) {
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            } else {
                ((TraceMetricTimer) spanOrTimer).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Object spanOrTimer) {
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithError(ErrorMessage.from(t));
            } else {
                ((TraceMetricTimer) spanOrTimer).stop();
            }
        }
    }

    @Pointcut(className = "java.sql.Connection", methodName = "setAutoCommit",
            ignoreSelfNested = true, methodParameterTypes = {"boolean"},
            traceMetric = "jdbc set autocommit")
    public static class SetAutoCommitAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(CloseAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && captureSetAutoCommitSpans;
        }
        @OnBefore
        public static Span onBefore(@BindParameter boolean autoCommit) {
            return pluginServices.startSpan(
                    MessageSupplier.from("jdbc set autocommit: {}", Boolean.toString(autoCommit)),
                    traceMetricName);
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
