/*
 * Copyright 2013-2018 the original author or authors.
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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import ch.qos.logback.core.Context;
import com.google.common.base.Ticker;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralCollector;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NonEmbeddedGlowrootAgentInit implements GlowrootAgentInit {

    private final @Nullable String collectorAddress;
    private final @Nullable String collectorAuthority;
    private final @Nullable Collector customCollector;

    private volatile @MonotonicNonNull PluginCache pluginCache;
    private volatile @MonotonicNonNull ConfigService configService;
    private volatile @MonotonicNonNull CollectorProxy collectorProxy;
    private volatile @MonotonicNonNull AgentModule agentModule;
    private volatile @MonotonicNonNull CentralCollector centralCollector;

    private volatile @MonotonicNonNull ScheduledExecutorService backgroundExecutor;

    private volatile @MonotonicNonNull Closeable agentDirsLockingCloseable;

    public NonEmbeddedGlowrootAgentInit(@Nullable String collectorAddress,
            @Nullable String collectorAuthority,
            @Nullable Collector customCollector) {
        this.collectorAddress = collectorAddress;
        this.collectorAuthority = collectorAuthority;
        this.customCollector = customCollector;
    }

    @Override
    public void init(@Nullable File pluginsDir, final File confDir,
            final @Nullable File sharedConfDir, File logDir, File tmpDir,
            final @Nullable File glowrootJarFile, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation,
            @Nullable ClassFileTransformer preCheckClassFileTransformer,
            final String glowrootVersion) throws Exception {

        agentDirsLockingCloseable = AgentDirsLocking.lockAgentDirs(tmpDir);
        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        // need to perform jrebel workaround prior to loading any jackson classes
        JRebelWorkaround.perform();
        pluginCache = PluginCache.create(pluginsDir, false);
        configService = ConfigService.create(confDir, pluginCache.pluginDescriptors());

        collectorProxy = new CollectorProxy();

        CollectorLogbackAppender collectorLogbackAppender =
                new CollectorLogbackAppender(collectorProxy);
        collectorLogbackAppender.setName(CollectorLogbackAppender.class.getName());
        collectorLogbackAppender.setContext((Context) LoggerFactory.getILoggerFactory());
        collectorLogbackAppender.start();
        attachAppender(collectorLogbackAppender);

        agentModule = new AgentModule(clock, ticker, pluginCache, configService, instrumentation,
                glowrootJarFile, tmpDir, preCheckClassFileTransformer);
        OnEnteringMain onEnteringMain = new OnEnteringMain() {
            @Override
            public void run() throws Exception {
                // TODO report checker framework issue that occurs without checkNotNull
                checkNotNull(pluginCache);
                checkNotNull(configService);
                checkNotNull(collectorProxy);
                checkNotNull(agentModule);
                ScheduledExecutorService backgroundExecutor = Executors.newScheduledThreadPool(2,
                        ThreadFactories.create("Glowroot-Background-%d"));
                agentModule.onEnteringMain(backgroundExecutor, collectorProxy, instrumentation,
                        glowrootJarFile);
                AgentConfigUpdater agentConfigUpdater =
                        new ConfigUpdateService(configService, pluginCache);
                NettyWorkaround.run();
                Collector collector;
                if (customCollector == null) {
                    centralCollector = new CentralCollector(properties,
                            checkNotNull(collectorAddress), collectorAuthority, confDir,
                            sharedConfDir, agentModule.getLiveJvmService(),
                            agentModule.getLiveWeavingService(),
                            agentModule.getLiveTraceRepository(), agentConfigUpdater);
                    collector = centralCollector;
                } else {
                    collector = customCollector;
                }
                collectorProxy.setInstance(collector);
                collector.init(confDir, sharedConfDir,
                        EnvironmentCreator.create(glowrootVersion,
                                agentModule.getConfigService().getJvmConfig()),
                        configService.getAgentConfig(), agentConfigUpdater);
            }
        };
        if (instrumentation == null) {
            onEnteringMain.run();
        } else {
            agentModule.setOnEnteringMain(onEnteringMain);
        }
    }

    @Override
    @OnlyUsedByTests
    public void setSlowThresholdToZero() throws IOException {
        AgentModule agentModule = checkNotNull(this.agentModule);
        agentModule.getConfigService().setSlowThresholdToZero();
    }

    @Override
    @OnlyUsedByTests
    public void resetConfig() throws Exception {
        AgentModule agentModule = checkNotNull(this.agentModule);
        agentModule.getConfigService().resetConfig();
        agentModule.getLiveWeavingService().reweave("");
    }

    @Override
    @OnlyUsedByTests
    public void close() throws Exception {
        checkNotNull(agentModule).close();
        if (centralCollector != null) {
            centralCollector.close();
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException("Could not terminate executor");
            }
        }
        // and unlock the agent directory
        if (agentDirsLockingCloseable != null) {
            agentDirsLockingCloseable.close();
        }
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() throws Exception {
        if (centralCollector != null) {
            centralCollector.awaitClose();
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
