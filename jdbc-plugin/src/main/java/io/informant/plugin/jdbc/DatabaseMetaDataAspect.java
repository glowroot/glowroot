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
package io.informant.plugin.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import checkers.nullness.quals.Nullable;

import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.MetricTimer;
import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.api.Span;
import io.informant.api.weaving.BindMethodName;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.Pointcut;
import io.informant.shaded.slf4j.Logger;
import io.informant.shaded.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DatabaseMetaDataAspect {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMetaDataAspect.class);

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:jdbc-plugin");

    // DatabaseMetaData method timings are captured below, so this thread local is used to
    // avoid capturing driver-specific java.sql.Statement executions used to implement the
    // method internally (especially since it is haphazard whether a particular driver
    // internally uses a java.sql API that is woven, or an internal API, or even a mis-matched
    // combination like using a PreparedStatement but not creating it via
    // Connection.prepareStatement())
    private static final ThreadLocal</*@Nullable*/String> currentlyExecutingMethodName =
            new ThreadLocal</*@Nullable*/String>();

    @Pointcut(typeName = "java.sql.DatabaseMetaData", methodName = "*", methodArgs = {".."},
            captureNested = false, metricName = "jdbc metadata")
    public static class AllMethodAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(AllMethodAdvice.class);
        // plugin configuration property captureDatabaseMetaDataSpans is cached to limit map lookups
        private static volatile boolean pluginEnabled;
        private static volatile boolean spanEnabled;
        static {
            pluginServices.registerConfigListener(new ConfigListener() {
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
        public static Object onBefore(@BindTarget DatabaseMetaData databaseMetaData,
                @BindMethodName String methodName) {
            currentlyExecutingMethodName.set(methodName);
            if (pluginServices.isEnabled()) {
                if (spanEnabled) {
                    return pluginServices.startSpan(MessageSupplier.from("jdbc metadata:"
                            + " DatabaseMetaData.{}() [connection: {}]", methodName,
                            getConnectionHashCode(databaseMetaData)), metricName);
                } else {
                    return pluginServices.startMetricTimer(metricName);
                }
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Object spanOrTimer) {
            // don't need to track prior value and reset to that value, since
            // @Pointcut.captureNested = false prevents re-entrant calls
            currentlyExecutingMethodName.remove();
            if (spanOrTimer == null) {
                return;
            }
            if (spanOrTimer instanceof Span) {
                ((Span) spanOrTimer).end();
            } else {
                ((MetricTimer) spanOrTimer).stop();
            }
        }
    }

    static boolean isCurrentlyExecuting() {
        return currentlyExecutingMethodName.get() != null;
    }

    static String getCurrentlyExecutingMethodName() {
        return currentlyExecutingMethodName.get();
    }

    private static String getConnectionHashCode(DatabaseMetaData databaseMetaData) {
        try {
            return Integer.toHexString(databaseMetaData.getConnection().hashCode());
        } catch (SQLException e) {
            logger.warn(e.getMessage(), e);
            return "???";
        }
    }
}
