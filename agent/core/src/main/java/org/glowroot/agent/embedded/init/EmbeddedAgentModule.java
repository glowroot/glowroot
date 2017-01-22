/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.embedded.init;

import java.io.Closeable;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.embedded.repo.PlatformMBeanServerLifecycle;
import org.glowroot.agent.embedded.repo.SimpleRepoModule;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.CollectorProxy;
import org.glowroot.agent.init.EnvironmentCreator;
import org.glowroot.agent.init.GlowrootThinAgentInit;
import org.glowroot.agent.init.JRebelWorkaround;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

class EmbeddedAgentModule {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final File baseDir;
    private final File glowrootDir;
    private final Ticker ticker;
    private final Clock clock;
    // only null in viewer mode
    private final @Nullable ScheduledExecutorService backgroundExecutor;
    private volatile @MonotonicNonNull SimpleRepoModule simpleRepoModule;
    private final @Nullable AgentModule agentModule;
    private final @Nullable ViewerAgentModule viewerAgentModule;

    private final Closeable dataDirLockingCloseable;

    private final String version;

    private final boolean h2MemDb;

    private volatile @MonotonicNonNull UiModule uiModule;

    private final CountDownLatch simpleRepoModuleInit = new CountDownLatch(1);

    EmbeddedAgentModule(final File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            final String glowrootVersion, boolean offline) throws Exception {

        dataDirLockingCloseable = DataDirLocking.lockDataDir(baseDir);

        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();

        // mem db is only used for testing (by glowroot-agent-it-harness)
        h2MemDb = Boolean.parseBoolean(properties.get("glowroot.internal.h2.memdb"));

        final File dataDir = new File(baseDir, "data");
        final DataSource dataSource;
        if (h2MemDb) {
            // mem db is only used for testing (by glowroot-agent-it-harness)
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "data.h2.db"));
        }

        // need to perform jrebel workaround prior to loading any jackson classes
        JRebelWorkaround.performWorkaroundIfNeeded();
        PluginCache pluginCache = PluginCache.create(glowrootJarFile, false);
        if (offline) {
            viewerAgentModule = new ViewerAgentModule(baseDir, glowrootJarFile);
            backgroundExecutor = null;
            agentModule = null;
            ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    viewerAgentModule.getConfigService(), pluginCache);
            simpleRepoModule = new SimpleRepoModule(dataSource, dataDir, clock, ticker,
                    configRepository, null);
            simpleRepoModuleInit.countDown();
        } else {
            // trace module needs to be started as early as possible, so that weaving will be
            // applied to as many classes as possible
            // in particular, it needs to be started before StorageModule which uses shaded H2,
            // which loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
            // services/java.sql.Driver, and those drivers need to be woven
            final CollectorProxy collectorProxy = new CollectorProxy();
            ConfigService configService =
                    ConfigService.create(baseDir, pluginCache.pluginDescriptors());

            // need to delay creation of the scheduled executor until instrumentation is set up
            Supplier<ScheduledExecutorService> backgroundExecutorSupplier =
                    GlowrootThinAgentInit.createBackgroundExecutorSupplier();

            agentModule = new AgentModule(clock, null, pluginCache, configService,
                    backgroundExecutorSupplier, collectorProxy, instrumentation, baseDir);

            backgroundExecutor = backgroundExecutorSupplier.get();

            PreInitializeStorageShutdownClasses.preInitializeClasses();
            final ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    agentModule.getConfigService(), pluginCache);
            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // TODO report checker framework issue that occurs without checkNotNull
                        checkNotNull(clock);
                        checkNotNull(ticker);
                        checkNotNull(agentModule);

                        SimpleRepoModule simpleRepoModule = new SimpleRepoModule(dataSource,
                                dataDir, clock, ticker, configRepository, backgroundExecutor);
                        simpleRepoModule.registerMBeans(new PlatformMBeanServerLifecycleImpl(
                                agentModule.getLazyPlatformMBeanServer()));

                        // now inject the real collector into the proxy
                        CollectorImpl collectorImpl = new CollectorImpl(
                                simpleRepoModule.getEnvironmentDao(),
                                simpleRepoModule.getAggregateDao(),
                                simpleRepoModule.getTraceDao(),
                                simpleRepoModule.getGaugeValueDao());
                        collectorProxy.setInstance(collectorImpl);
                        // embedded CollectorImpl does nothing with agent config parameter
                        collectorImpl.init(baseDir, EnvironmentCreator.create(glowrootVersion),
                                AgentConfig.getDefaultInstance(), new AgentConfigUpdater() {
                                    @Override
                                    public void update(AgentConfig agentConfig) {}
                                });

                        EmbeddedAgentModule.this.simpleRepoModule = simpleRepoModule;

                    } catch (Throwable t) {
                        startupLogger.error("Glowroot cannot start: {}", t.getMessage(), t);
                    } finally {
                        // TODO report checker framework issue that occurs without checkNotNull
                        checkNotNull(simpleRepoModuleInit).countDown();
                    }
                }
            });
            // prefer to wait for repo to start up on its own, then no worry about losing collected
            // data due to limits in CollectorProxy, but don't wait too long as first launch after
            // upgrade when adding new columns to large H2 database can take some time
            simpleRepoModuleInit.await(5, SECONDS);
            viewerAgentModule = null;
        }

        this.baseDir = baseDir;
        if (glowrootJarFile == null) {
            glowrootDir = baseDir;
        } else {
            glowrootDir = MoreObjects.firstNonNull(glowrootJarFile.getParentFile(), baseDir);
        }
        this.version = glowrootVersion;
    }

    void initEmbeddedServer() throws Exception {
        if (simpleRepoModule == null) {
            // repo module failed to start
            return;
        }
        if (agentModule != null) {
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .offline(false)
                    .baseDir(baseDir)
                    .glowrootDir(glowrootDir)
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .agentRepository(new AgentRepositoryImpl())
                    .environmentRepository(simpleRepoModule.getEnvironmentDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .traceAttributeNameRepository(
                            simpleRepoModule.getTraceAttributeNameRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateDao())
                    .traceRepository(simpleRepoModule.getTraceDao())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueDao())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(agentModule.getLiveTraceRepository())
                    .liveAggregateRepository(agentModule.getLiveAggregateRepository())
                    .liveWeavingService(agentModule.getLiveWeavingService())
                    .numWorkerThreads(2)
                    .version(version)
                    .build();
        } else {
            checkNotNull(viewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .offline(true)
                    .baseDir(baseDir)
                    .glowrootDir(glowrootDir)
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(null)
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .agentRepository(new AgentRepositoryImpl())
                    .environmentRepository(simpleRepoModule.getEnvironmentDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .traceAttributeNameRepository(
                            simpleRepoModule.getTraceAttributeNameRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateDao())
                    .traceRepository(simpleRepoModule.getTraceDao())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueDao())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(new LiveTraceRepositoryNop())
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(null)
                    .numWorkerThreads(10)
                    .version(version)
                    .build();
        }
    }

    boolean isSimpleRepoModuleReady() throws InterruptedException {
        return simpleRepoModuleInit.await(0, SECONDS);
    }

    void waitForSimpleRepoModule() throws InterruptedException {
        simpleRepoModuleInit.await();
    }

    @OnlyUsedByTests
    public SimpleRepoModule getSimpleRepoModule() throws InterruptedException {
        simpleRepoModuleInit.await();
        return checkNotNull(simpleRepoModule);
    }

    @OnlyUsedByTests
    public AgentModule getAgentModule() {
        checkNotNull(agentModule);
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

    @OnlyUsedByTests
    public void close() throws Exception {
        if (uiModule != null) {
            uiModule.close(true);
        }
        if (agentModule != null) {
            agentModule.close();
        }
        checkNotNull(simpleRepoModule).close();
        if (backgroundExecutor != null) {
            // close background executor last to prevent exceptions due to above modules attempting
            // to use a shutdown executor
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException("Could not terminate executor");
            }
        }
        // and unlock the data directory
        dataDirLockingCloseable.close();
    }

    private static class PlatformMBeanServerLifecycleImpl implements PlatformMBeanServerLifecycle {

        private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

        private PlatformMBeanServerLifecycleImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer) {
            this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        }

        @Override
        public void lazyRegisterMBean(Object object, String name) {
            lazyPlatformMBeanServer.lazyRegisterMBean(object, name);
        }
    }

    private static class AgentRepositoryImpl implements AgentRepository {

        @Override
        public List<AgentRollup> readAgentRollups() {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .agent(true)
                    .build());
        }

        @Override
        public String readAgentRollupDisplay(String agentRollupId) {
            return "";
        }

        @Override
        public boolean isAgent(String agentId) {
            return true;
        }
    }
}
