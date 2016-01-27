/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import ch.qos.logback.core.Context;
import com.google.common.base.Ticker;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralModule;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.PreInitializeWeavingClasses;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector;

import static com.google.common.base.Preconditions.checkNotNull;

public class GlowrootThinAgentInit implements GlowrootAgentInit {

    private @MonotonicNonNull AgentModule agentModule;
    private @MonotonicNonNull CentralModule centralModule;

    @Override
    public void init(final File baseDir, final @Nullable String collectorHost,
            final @Nullable Collector customCollector, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            final String glowrootVersion, boolean viewerMode) throws Exception {

        if (instrumentation != null) {
            PreInitializeWeavingClasses.preInitializeClasses();
        }
        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        PluginCache pluginCache = PluginCache.create(glowrootJarFile, false);
        final ConfigService configService =
                ConfigService.create(baseDir, pluginCache.pluginDescriptors());

        final CollectorProxy collectorProxy = new CollectorProxy();

        CollectorLogbackAppender collectorLogbackAppender =
                new CollectorLogbackAppender(collectorProxy);
        collectorLogbackAppender.setName(CollectorLogbackAppender.class.getName());
        collectorLogbackAppender.setContext((Context) LoggerFactory.getILoggerFactory());
        collectorLogbackAppender.start();
        attachAppender(collectorLogbackAppender);

        final AgentModule agentModule = new AgentModule(clock, ticker, pluginCache, configService,
                collectorProxy, instrumentation, baseDir);

        NettyWorkaround.run(instrumentation, new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                if (customCollector != null) {
                    collectorProxy.setInstance(customCollector);
                    return null;
                }
                centralModule = new CentralModule(properties, collectorHost, configService,
                        agentModule.getLiveWeavingService(), agentModule.getLiveJvmService(),
                        agentModule.getScheduledExecutor(), glowrootVersion);
                collectorProxy.setInstance(centralModule.getGrpcCollector());
                return null;
            }
        });
        this.agentModule = agentModule;
    }

    @Override
    @OnlyUsedByTests
    public AgentModule getAgentModule() {
        return checkNotNull(agentModule);
    }

    @Override
    @OnlyUsedByTests
    public void close() throws Exception {
        checkNotNull(agentModule).close();
        if (centralModule != null) {
            centralModule.close();
        }
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() throws Exception {
        if (centralModule != null) {
            centralModule.awaitClose();
        }
    }

    private static void attachAppender(CollectorLogbackAppender appender) {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        // detaching existing appender first is for tests
        rootLogger.detachAppender(appender.getClass().getName());
        rootLogger.addAppender(appender);
    }
}
