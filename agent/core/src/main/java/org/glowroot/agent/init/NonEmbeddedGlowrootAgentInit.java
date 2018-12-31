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
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import ch.qos.logback.core.Context;
import com.google.common.base.Ticker;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralCollector;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.init.PreCheckLoadedClasses.PreCheckClassFileTransformer;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NonEmbeddedGlowrootAgentInit implements GlowrootAgentInit {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final Logger logger =
            LoggerFactory.getLogger(NonEmbeddedGlowrootAgentInit.class);

    private final @Nullable String collectorAddress;
    private final @Nullable String collectorAuthority;
    private final @Nullable Class<? extends Collector> customCollectorClass;

    private volatile @MonotonicNonNull CollectorLogbackAppender collectorLogbackAppender;
    private volatile @MonotonicNonNull AgentModule agentModule;
    private volatile @MonotonicNonNull CentralCollector centralCollector;

    private volatile @MonotonicNonNull ScheduledExecutorService backgroundExecutor;

    private volatile @MonotonicNonNull Closeable agentDirLockCloseable;

    public NonEmbeddedGlowrootAgentInit(@Nullable String collectorAddress,
            @Nullable String collectorAuthority,
            @Nullable Class<? extends Collector> customCollectorClass) {
        this.collectorAddress = collectorAddress;
        this.collectorAuthority = collectorAuthority;
        this.customCollectorClass = customCollectorClass;
    }

    @Override
    public void init(@Nullable File pluginsDir, final List<File> confDirs, File logDir, File tmpDir,
            final @Nullable File glowrootJarFile, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation,
            @Nullable PreCheckClassFileTransformer preCheckClassFileTransformer,
            final String glowrootVersion, Closeable agentDirLockCloseable) throws Exception {

        this.agentDirLockCloseable = agentDirLockCloseable;
        Ticker ticker = Tickers.getTicker();
        Clock clock = Clock.systemClock();

        // need to perform jrebel workaround prior to loading any jackson classes
        JRebelWorkaround.perform();
        final boolean configReadOnly =
                Boolean.parseBoolean(properties.get("glowroot.config.readOnly"));
        final PluginCache pluginCache = PluginCache.create(pluginsDir, false);
        final ConfigService configService =
                ConfigService.create(confDirs, configReadOnly, pluginCache.pluginDescriptors());

        final CollectorProxy collectorProxy = new CollectorProxy();

        collectorLogbackAppender = new CollectorLogbackAppender(collectorProxy);
        collectorLogbackAppender.setName(CollectorLogbackAppender.class.getName());
        collectorLogbackAppender.setContext((Context) LoggerFactory.getILoggerFactory());
        collectorLogbackAppender.start();
        attachAppender(collectorLogbackAppender);

        agentModule = new AgentModule(clock, ticker, pluginCache, configService, instrumentation,
                glowrootJarFile, tmpDir, preCheckClassFileTransformer);
        OnEnteringMain onEnteringMain = new OnEnteringMain() {
            @Override
            public void run(@Nullable String mainClass) throws Exception {
                // TODO report checker framework issue that occurs without checkNotNull
                checkNotNull(agentModule);
                backgroundExecutor = Executors.newScheduledThreadPool(2,
                        ThreadFactories.create("Glowroot-Background-%d"));
                agentModule.onEnteringMain(backgroundExecutor, collectorProxy, instrumentation,
                        glowrootJarFile, mainClass);
                AgentConfigUpdater agentConfigUpdater =
                        new ConfigUpdateService(configService, pluginCache);
                NettyInit.run();
                Collector collector;
                Constructor<? extends Collector> collectorProxyConstructor = null;
                if (customCollectorClass != null) {
                    try {
                        collectorProxyConstructor =
                                customCollectorClass.getConstructor(Collector.class);
                    } catch (NoSuchMethodException e) {
                        logger.debug(e.getMessage(), e);
                    }
                }
                if (customCollectorClass != null && collectorProxyConstructor == null) {
                    collector = customCollectorClass.newInstance();
                } else {
                    centralCollector = new CentralCollector(properties,
                            checkNotNull(collectorAddress), collectorAuthority, confDirs,
                            configReadOnly, agentModule.getLiveJvmService(),
                            agentModule.getLiveWeavingService(),
                            agentModule.getLiveTraceRepository(), agentConfigUpdater,
                            configService);
                    if (collectorProxyConstructor == null) {
                        collector = centralCollector;
                    } else {
                        startupLogger.info("using collector proxy: {}",
                                collectorProxyConstructor.getName());
                        collector = collectorProxyConstructor.newInstance(centralCollector);
                    }
                }
                collectorProxy.setInstance(collector);
                collector.init(confDirs,
                        EnvironmentCreator.create(glowrootVersion, configService.getJvmConfig()),
                        configService.getAgentConfig(), agentConfigUpdater);
            }
        };
        if (instrumentation == null) {
            onEnteringMain.run(null);
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
        checkNotNull(collectorLogbackAppender).close();
        // and unlock the agent directory
        checkNotNull(agentDirLockCloseable).close();
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
