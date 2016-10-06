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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;

import ch.qos.logback.core.Context;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralCollector;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.PreInitializeWeavingClasses;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class GlowrootThinAgentInit implements GlowrootAgentInit {

    private @MonotonicNonNull AgentModule agentModule;
    private @MonotonicNonNull CentralCollector centralCollector;

    private @MonotonicNonNull ScheduledExecutorService backgroundExecutor;

    @Override
    public void init(final File baseDir, final @Nullable String collectorHost,
            final @Nullable Collector customCollector, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            final String glowrootVersion, boolean offlineViewer) throws Exception {

        if (instrumentation != null) {
            PreInitializeWeavingClasses.preInitializeClasses();
        }
        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        final PluginCache pluginCache = PluginCache.create(glowrootJarFile, false);
        final ConfigService configService =
                ConfigService.create(baseDir, pluginCache.pluginDescriptors());

        final CollectorProxy collectorProxy = new CollectorProxy();

        CollectorLogbackAppender collectorLogbackAppender =
                new CollectorLogbackAppender(collectorProxy);
        collectorLogbackAppender.setName(CollectorLogbackAppender.class.getName());
        collectorLogbackAppender.setContext((Context) LoggerFactory.getILoggerFactory());
        collectorLogbackAppender.start();
        attachAppender(collectorLogbackAppender);

        // need to delay creation of the scheduled executor until instrumentation is set up
        Supplier<ScheduledExecutorService> backgroundExecutorSupplier =
                createBackgroundExecutorSupplier();

        final AgentModule agentModule = new AgentModule(clock, ticker, pluginCache, configService,
                backgroundExecutorSupplier, collectorProxy, instrumentation, baseDir);

        final ScheduledExecutorService backgroundExecutor = backgroundExecutorSupplier.get();

        final AgentConfigUpdater agentConfigUpdater =
                new ConfigUpdateService(configService, pluginCache);

        NettyWorkaround.run(instrumentation, new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                Collector collector;
                if (customCollector == null) {
                    centralCollector = new CentralCollector(properties,
                            checkNotNull(collectorHost), agentModule.getLiveJvmService(),
                            agentModule.getLiveWeavingService(),
                            agentModule.getLiveTraceRepository(), agentConfigUpdater);
                    collector = centralCollector;
                } else {
                    collector = customCollector;
                }
                collectorProxy.setInstance(collector);
                collector.init(baseDir, EnvironmentCreator.create(glowrootVersion),
                        configService.getAgentConfig(), agentConfigUpdater);
                return null;
            }
        });
        this.agentModule = agentModule;
        this.backgroundExecutor = backgroundExecutor;
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
        if (centralCollector != null) {
            centralCollector.close();
        }
        checkNotNull(backgroundExecutor);
        backgroundExecutor.shutdown();
        if (!backgroundExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() throws Exception {
        if (centralCollector != null) {
            centralCollector.awaitClose();
        }
    }

    public static Supplier<ScheduledExecutorService> createBackgroundExecutorSupplier() {
        return Suppliers.memoize(new Supplier<ScheduledExecutorService>() {
            @Override
            public ScheduledExecutorService get() {
                final ThreadFactory backingThreadFactory = new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-Background-%d")
                        .build();
                ThreadFactory threadFactory = new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = backingThreadFactory.newThread(r);
                        thread.setContextClassLoader(GlowrootThinAgentInit.class.getClassLoader());
                        return thread;
                    }
                };
                return Executors.newScheduledThreadPool(2, threadFactory);
            }
        });
    }

    private static void attachAppender(CollectorLogbackAppender appender) {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        // detaching existing appender first is for tests
        rootLogger.detachAppender(appender.getClass().getName());
        rootLogger.addAppender(appender);
    }
}
