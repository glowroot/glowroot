/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.fat;

import java.io.Closeable;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;
import javax.management.MBeanServer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.AgentModule;
import org.glowroot.agent.Collector;
import org.glowroot.agent.DataDirLocking;
import org.glowroot.agent.LoggingInit;
import org.glowroot.agent.ViewerAgentModule;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveThreadDumpService.LiveThreadDumpServiceNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.live.LiveWeavingService.LiveWeavingServiceNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.simplerepo.PlatformMBeanServerLifecycle;
import org.glowroot.storage.simplerepo.SimpleRepoModule;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class GlowrootModule {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootFatAgentInit.class);

    // this static field is only present for tests
    private static volatile @MonotonicNonNull GlowrootModule INSTANCE;

    private final Ticker ticker;
    private final Clock clock;
    // only null in viewer mode
    private final @Nullable ScheduledExecutorService scheduledExecutor;
    private final @Nullable SimpleRepoModule simpleRepoModule;
    private final @Nullable AgentModule agentModule;
    private final @Nullable ViewerAgentModule viewerAgentModule;
    private final File baseDir;

    private final Closeable dataDirLockingCloseable;

    private final String bindAddress;
    private final String version;

    private final boolean h2MemDb;

    private volatile @MonotonicNonNull UiModule uiModule;

    GlowrootModule(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String glowrootVersion, boolean jbossModules, boolean viewerMode) throws Exception {

        dataDirLockingCloseable = DataDirLocking.lockDataDir(baseDir);

        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();

        // mem db is only used for testing (by glowroot-test-container)
        h2MemDb = Boolean.parseBoolean(properties.get("internal.h2.memdb"));

        if (viewerMode) {
            viewerAgentModule = new ViewerAgentModule(baseDir, glowrootJarFile);
            scheduledExecutor = null;
            agentModule = null;
            ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    viewerAgentModule.getPluginDescriptors(), viewerAgentModule.getConfigService());
            PlatformMBeanServerLifecycle platformMBeanServerLifecycle =
                    new PlatformMBeanServerLifecycleImpl(
                            viewerAgentModule.getLazyPlatformMBeanServer());
            simpleRepoModule = new SimpleRepoModule(baseDir, clock, ticker, configRepository, null,
                    platformMBeanServerLifecycle, h2MemDb, true);
        } else {
            ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                    .setNameFormat("Glowroot-Background-%d").build();
            scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
            // trace module needs to be started as early as possible, so that weaving will be
            // applied to as many classes as possible
            // in particular, it needs to be started before StorageModule which uses shaded H2,
            // which loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
            // services/java.sql.Driver, and those drivers need to be woven
            CollectorProxy collectorProxy = new CollectorProxy();
            agentModule = new AgentModule(clock, null, collectorProxy, instrumentation, baseDir,
                    glowrootJarFile, scheduledExecutor, jbossModules);

            PreInitializeStorageShutdownClasses.preInitializeClasses();
            ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    agentModule.getPluginDescriptors(), agentModule.getConfigService());
            PlatformMBeanServerLifecycle platformMBeanServerLifecycle =
                    new PlatformMBeanServerLifecycleImpl(agentModule.getLazyPlatformMBeanServer());
            simpleRepoModule = new SimpleRepoModule(baseDir, clock, ticker, configRepository,
                    scheduledExecutor, platformMBeanServerLifecycle, h2MemDb, false);
            // now inject the real collector into the proxy

            CollectorImpl collectorImpl =
                    new CollectorImpl(simpleRepoModule.getAggregateRepository(),
                            simpleRepoModule.getTraceRepository(),
                            simpleRepoModule.getGaugeValueRepository(),
                            simpleRepoModule.getAlertingService());

            collectorProxy.setInstance(collectorImpl);
            viewerAgentModule = null;
        }

        bindAddress = getBindAddress(properties);
        this.baseDir = baseDir;
        this.version = glowrootVersion;
    }

    void initEmbeddedServerLazy(final Instrumentation instrumentation) {
        if (simpleRepoModule == null) {
            // using custom collector with no UI
            return;
        }
        // cannot start netty in premain otherwise can crash JVM
        // see https://github.com/netty/netty/issues/3233
        // and https://bugs.openjdk.java.net/browse/JDK-8041920
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForMain(instrumentation);
                    initEmbeddedServer();
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        });
    }

    void initEmbeddedServer() throws Exception {
        if (simpleRepoModule == null) {
            // using custom collector with no UI
            return;
        }
        if (agentModule != null) {
            uiModule = new CreateUiModuleBuilder()
                    .ticker(ticker)
                    .clock(clock)
                    .baseDir(baseDir)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .traceRepository(simpleRepoModule.getTraceRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateRepository())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueRepository())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .liveTraceRepository(agentModule.getLiveTraceRepository())
                    .liveThreadDumpService(agentModule.getLiveThreadDumpService())
                    .liveAggregateRepository(agentModule.getLiveAggregateRepository())
                    .liveWeavingService(agentModule.getLiveWeavingService())
                    .viewerMode(false)
                    .bindAddress(bindAddress)
                    .version(version)
                    .pluginDescriptors(agentModule.getPluginDescriptors())
                    .build();
        } else {
            checkNotNull(viewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .ticker(ticker)
                    .clock(clock)
                    .baseDir(baseDir)
                    .liveJvmService(viewerAgentModule.getLiveJvmService())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .traceRepository(simpleRepoModule.getTraceRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateRepository())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueRepository())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .liveTraceRepository(new LiveTraceRepositoryNop())
                    .liveThreadDumpService(new LiveThreadDumpServiceNop())
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceNop())
                    .viewerMode(true)
                    .bindAddress(bindAddress)
                    .version(version)
                    .pluginDescriptors(viewerAgentModule.getPluginDescriptors())
                    .build();
        }
    }

    static void setInstance(GlowrootModule module) {
        INSTANCE = module;
    }

    private static void waitForMain(Instrumentation instrumentation) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            Thread.sleep(100);
            for (Class<?> clazz : instrumentation.getInitiatedClasses(null)) {
                if (clazz.getName().equals("sun.misc.Launcher")) {
                    return;
                }
            }
        }
        // something has gone wrong
        logger.error("sun.misc.Launcher was never loaded");
    }

    private static String getBindAddress(Map<String, String> properties) {
        // empty check to support parameterized script, e.g. -Dglowroot.ui.bind.address=${somevar}
        String bindAddress = properties.get("ui.bind.address");
        if (Strings.isNullOrEmpty(bindAddress)) {
            return "0.0.0.0";
        } else {
            return bindAddress;
        }
    }

    @OnlyUsedByTests
    public SimpleRepoModule getSimpleRepoModule() {
        // simpleRepoModule is always used by tests
        checkNotNull(simpleRepoModule);
        return simpleRepoModule;
    }

    @OnlyUsedByTests
    public @Nullable AgentModule getAgentModule() {
        return agentModule;
    }

    @OnlyUsedByTests
    public UiModule getUiModule() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            if (uiModule != null) {
                return uiModule;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("UI Module failed to start");
    }

    // this is used to re-open a shared container after a non-shared container was used
    @OnlyUsedByTests
    public void reopen() throws Exception {
        INSTANCE = this;
        // this is not called by viewer
        checkNotNull(agentModule);
        agentModule.reopen();
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (uiModule != null) {
            uiModule.close();
        }
        if (agentModule != null) {
            agentModule.close();
        }
        // simpleRepoModule is always used by tests
        checkNotNull(simpleRepoModule);
        simpleRepoModule.close();
        if (scheduledExecutor != null) {
            // close scheduled executor last to prevent exceptions due to above modules attempting
            // to use a shutdown executor
            scheduledExecutor.shutdownNow();
        }
        // finally, close logger
        LoggingInit.close();
        // and unlock the data directory
        dataDirLockingCloseable.close();
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootModule getInstance() {
        return INSTANCE;
    }

    @VisibleForTesting
    static class CollectorProxy implements Collector {

        private volatile @MonotonicNonNull Collector instance;

        @Override
        public void collectAggregates(long captureTime, List<OverallAggregate> overallAggregates,
                List<TransactionAggregate> transactionAggregates) throws Exception {
            if (instance != null) {
                instance.collectAggregates(captureTime, overallAggregates, transactionAggregates);
            }
        }

        @Override
        public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
            if (instance != null) {
                instance.collectGaugeValues(gaugeValues);
            }
        }

        @Override
        public void collectTrace(Trace trace) throws Exception {
            if (instance != null) {
                instance.collectTrace(trace);
            }
        }

        @VisibleForTesting
        void setInstance(Collector instance) {
            this.instance = instance;
        }
    }

    private static class PlatformMBeanServerLifecycleImpl implements PlatformMBeanServerLifecycle {

        private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

        private PlatformMBeanServerLifecycleImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer) {
            this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        }

        @Override
        public void addInitListener(final InitListener listener) {
            lazyPlatformMBeanServer.addInitListener(
                    new org.glowroot.agent.util.LazyPlatformMBeanServer.InitListener() {
                        @Override
                        public void postInit(MBeanServer mbeanServer) throws Exception {
                            listener.doWithPlatformMBeanServer(mbeanServer);
                        }
                    });
        }
    }
}
