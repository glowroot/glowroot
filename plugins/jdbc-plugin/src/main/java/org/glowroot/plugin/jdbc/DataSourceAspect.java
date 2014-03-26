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

import java.sql.Connection;
import java.sql.SQLException;

import checkers.nullness.quals.MonotonicNonNull;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindReturn;
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
// DataSource.getConnection() can be interesting in case the data source is improperly sized and is
// slow while expanding
public class DataSourceAspect {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceAspect.class);

    private static final PluginServices pluginServices = PluginServices.get("jdbc");

    private static volatile boolean captureGetConnectionSpans;
    private static volatile boolean captureSetAutoCommitSpans;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureGetConnectionSpans =
                        pluginServices.getBooleanProperty("captureGetConnectionSpans");
                captureSetAutoCommitSpans =
                        pluginServices.getBooleanProperty("captureSetAutoCommitSpans");
            }
        });
        captureGetConnectionSpans = pluginServices.getBooleanProperty("captureGetConnectionSpans");
        captureSetAutoCommitSpans = pluginServices.getBooleanProperty("captureSetAutoCommitSpans");
    }

    @Pointcut(typeName = "javax.sql.DataSource", methodName = "getConnection",
            methodArgs = {".."}, ignoreSameNested = true, metricName = "jdbc get connection")
    public static class CommitAdvice {
        private static final MetricName metricName = MetricName.get(CommitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Object onBefore() {
            if (captureGetConnectionSpans) {
                return pluginServices.startSpan(new GetConnectionMessageSupplier(), metricName);
            } else {
                return pluginServices.startMetricTimer(metricName);
            }
        }
        @OnReturn
        public static void onReturn(@BindReturn Connection connection,
                @BindTraveler Object spanOrTimer) {
            if (spanOrTimer instanceof Span) {
                Span span = (Span) spanOrTimer;
                if (captureSetAutoCommitSpans) {
                    String autoCommit;
                    try {
                        autoCommit = Boolean.toString(connection.getAutoCommit());
                    } catch (SQLException e) {
                        logger.warn(e.getMessage(), e);
                        autoCommit = "unknown";
                    }
                    GetConnectionMessageSupplier messageSupplier =
                            (GetConnectionMessageSupplier) span.getMessageSupplier();
                    if (messageSupplier != null) {
                        // messageSupplier can be null if NopSpan
                        messageSupplier.setAutoCommit(autoCommit);
                    }
                }
                span.endWithStackTrace(JdbcPluginProperties.stackTraceThresholdMillis(),
                        MILLISECONDS);
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

    private static class GetConnectionMessageSupplier extends MessageSupplier {

        @MonotonicNonNull
        private volatile String autoCommit;

        @Override
        public Message get() {
            if (autoCommit == null) {
                return Message.from("jdbc get connection");
            } else {
                return Message.from("jdbc get connection (autocommit: {})", autoCommit);
            }
        }

        private void setAutoCommit(String autoCommit) {
            this.autoCommit = autoCommit;
        }
    }
}
