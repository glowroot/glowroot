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

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.TraceMetricName;
import org.glowroot.api.TraceMetricTimer;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DatabaseMetaDataAspect {

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    // DatabaseMetaData method timings are captured below, so this thread local is used to
    // avoid capturing driver-specific java.sql.Statement executions used to implement the
    // method internally (especially since it is haphazard whether a particular driver
    // internally uses a java.sql API that is woven, or an internal API, or even a mis-matched
    // combination like using a PreparedStatement but not creating it via
    // Connection.prepareStatement())
    private static final ThreadLocal</*@Nullable*/String> currentlyExecutingMethodName =
            new ThreadLocal</*@Nullable*/String>();

    @Pointcut(type = "java.sql.DatabaseMetaData", methodName = "*", methodArgTypes = {".."},
            ignoreSameNested = true, traceMetric = "jdbc metadata")
    public static class AllMethodAdvice {
        private static final TraceMetricName traceMetricName =
                pluginServices.getTraceMetricName(AllMethodAdvice.class);
        // plugin configuration property captureDatabaseMetaDataSpans is cached to limit map lookups
        private static volatile boolean pluginEnabled;
        private static volatile boolean spanEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
                @Override
                public void onChange() {
                    pluginEnabled = pluginServices.isEnabled();
                    spanEnabled = pluginEnabled
                            && pluginServices.getBooleanProperty("captureDatabaseMetaDataSpans");
                }
            });
            pluginEnabled = pluginServices.isEnabled();
            spanEnabled = pluginEnabled
                    && pluginServices.getBooleanProperty("captureDatabaseMetaDataSpans");
        }
        @OnBefore
        @Nullable
        public static Object onBefore(@BindMethodName String methodName) {
            currentlyExecutingMethodName.set(methodName);
            if (pluginServices.isEnabled()) {
                if (spanEnabled) {
                    return pluginServices.startSpan(MessageSupplier.from("jdbc metadata:"
                            + " DatabaseMetaData.{}()", methodName), traceMetricName);
                } else {
                    return pluginServices.startTraceMetric(traceMetricName);
                }
            } else {
                return null;
            }
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable Object spanOrTimer) {
            // don't need to track prior value and reset to that value, since
            // @Pointcut.ignoreSameNested = true prevents re-entrant calls
            currentlyExecutingMethodName.remove();
            if (spanOrTimer == null) {
                return;
            }
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithStackTrace(
                        JdbcPluginProperties.stackTraceThresholdMillis(), MILLISECONDS);
            } else {
                ((TraceMetricTimer) spanOrTimer).stop();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable Object spanOrTimer) {
            // don't need to track prior value and reset to that value, since
            // @Pointcut.ignoreSameNested = true prevents re-entrant calls
            currentlyExecutingMethodName.remove();
            if (spanOrTimer == null) {
                return;
            }
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).endWithError(ErrorMessage.from(t));
            } else {
                ((TraceMetricTimer) spanOrTimer).stop();
            }
        }
    }

    static boolean isCurrentlyExecuting() {
        return currentlyExecutingMethodName.get() != null;
    }

    @Nullable
    static String getCurrentlyExecutingMethodName() {
        return currentlyExecutingMethodName.get();
    }
}
