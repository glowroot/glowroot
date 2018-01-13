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
package org.glowroot.agent.embedded.init;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.embedded.repo.ConfigRepositoryImpl;
import org.glowroot.agent.embedded.repo.PlatformMBeanServerLifecycle;
import org.glowroot.agent.embedded.repo.SimpleRepoModule;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.init.AgentDirsLocking;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.CollectorProxy;
import org.glowroot.agent.init.EnvironmentCreator;
import org.glowroot.agent.init.JRebelWorkaround;
import org.glowroot.agent.init.NonEmbeddedGlowrootAgentInit;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.config.ImmutableRoleConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.RoleConfig.SimplePermission;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.SessionMapFactory;
import org.glowroot.ui.UiModule;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

class EmbeddedAgentModule {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final File logDir;
    private final File confDir;
    private final @Nullable File sharedConfDir;
    private final Ticker ticker;
    private final Clock clock;
    // only null in viewer mode
    private final @Nullable ScheduledExecutorService backgroundExecutor;
    private volatile @MonotonicNonNull SimpleRepoModule simpleRepoModule;
    private final @Nullable AgentModule agentModule;
    private final @Nullable ViewerAgentModule viewerAgentModule;

    private final Closeable agentDirsLockingCloseable;

    private final String version;

    private volatile @MonotonicNonNull UiModule uiModule;

    private final CountDownLatch simpleRepoModuleInit = new CountDownLatch(1);

    EmbeddedAgentModule(@Nullable File pluginsDir, final File confDir,
            final @Nullable File sharedConfDir, File logDir, final File dataDir, File tmpDir,
            @Nullable File glowrootJarFile, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, final String glowrootVersion,
            boolean offline) throws Exception {

        agentDirsLockingCloseable = AgentDirsLocking.lockAgentDirs(tmpDir);

        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();

        // mem db is only used for testing (by glowroot-agent-it-harness)
        final boolean h2MemDb = Boolean.parseBoolean(properties.get("glowroot.internal.h2.memdb"));

        // need to perform jrebel workaround prior to loading any jackson classes
        JRebelWorkaround.perform();
        PluginCache pluginCache = PluginCache.create(pluginsDir, false);
        if (offline) {
            viewerAgentModule = new ViewerAgentModule(pluginsDir, confDir);
            backgroundExecutor = null;
            agentModule = null;
            ConfigRepositoryImpl configRepository = ConfigRepositoryImpl.create(confDir,
                    viewerAgentModule.getConfigService(), pluginCache);
            DataSource dataSource = createDataSource(h2MemDb, dataDir);
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
                    ConfigService.create(confDir, pluginCache.pluginDescriptors());

            // need to delay creation of the scheduled executor until instrumentation is set up
            Supplier<ScheduledExecutorService> backgroundExecutorSupplier =
                    NonEmbeddedGlowrootAgentInit.createBackgroundExecutorSupplier();

            agentModule = new AgentModule(clock, null, pluginCache, configService,
                    backgroundExecutorSupplier, collectorProxy, instrumentation, tmpDir,
                    glowrootJarFile);

            backgroundExecutor = backgroundExecutorSupplier.get();

            PreInitializeStorageShutdownClasses.preInitializeClasses();
            final ConfigRepositoryImpl configRepository = ConfigRepositoryImpl.create(confDir,
                    agentModule.getConfigService(), pluginCache);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // TODO report checker framework issue that occurs without checkNotNull
                        checkNotNull(clock);
                        checkNotNull(ticker);
                        checkNotNull(agentModule);
                        DataSource dataSource = createDataSource(h2MemDb, dataDir);
                        if (needToAddAlertPermission(dataSource)) {
                            addAlertPermission(configRepository);
                        }
                        SimpleRepoModule simpleRepoModule = new SimpleRepoModule(dataSource,
                                dataDir, clock, ticker, configRepository, backgroundExecutor);
                        simpleRepoModule.registerMBeans(new PlatformMBeanServerLifecycleImpl(
                                agentModule.getLazyPlatformMBeanServer()));
                        // now inject the real collector into the proxy
                        CollectorImpl collectorImpl = new CollectorImpl(
                                simpleRepoModule.getEnvironmentDao(),
                                simpleRepoModule.getAggregateDao(), simpleRepoModule.getTraceDao(),
                                simpleRepoModule.getGaugeValueDao(), configRepository,
                                simpleRepoModule.getAlertingService(),
                                simpleRepoModule.getHttpClient());
                        collectorProxy.setInstance(collectorImpl);
                        // embedded CollectorImpl does nothing with agent config parameter
                        collectorImpl.init(confDir, sharedConfDir,
                                EnvironmentCreator.create(glowrootVersion,
                                        agentModule.getConfigService().getJvmConfig()),
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
            thread.setName("Glowroot-Init-Repo");
            thread.setDaemon(true);
            thread.start();

            // prefer to wait for repo to start up on its own, then no worry about losing collected
            // data due to limits in CollectorProxy, but don't wait too long as first launch after
            // upgrade when adding new columns to large H2 database can take some time
            simpleRepoModuleInit.await(5, SECONDS);
            viewerAgentModule = null;
        }
        this.confDir = confDir;
        this.sharedConfDir = sharedConfDir;
        this.logDir = logDir;
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
                    .servlet(false)
                    .offline(false)
                    .confDir(confDir)
                    .sharedConfDir(sharedConfDir)
                    .logDir(logDir)
                    .logFileNamePattern(Pattern.compile("glowroot.*\\.log"))
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .agentRollupRepository(new AgentRollupRepositoryImpl())
                    .environmentRepository(simpleRepoModule.getEnvironmentDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .traceAttributeNameRepository(
                            simpleRepoModule.getTraceAttributeNameRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateDao())
                    .traceRepository(simpleRepoModule.getTraceDao())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueDao())
                    .syntheticResultRepository(null)
                    .incidentRepository(simpleRepoModule.getIncidentDao())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(agentModule.getLiveTraceRepository())
                    .liveAggregateRepository(agentModule.getLiveAggregateRepository())
                    .liveWeavingService(agentModule.getLiveWeavingService())
                    .sessionMapFactory(new SessionMapFactory() {
                        @Override
                        public <V extends /*@NonNull*/ Serializable> ConcurrentMap<String, V> create() {
                            return Maps.newConcurrentMap();
                        }
                    })
                    .httpClient(simpleRepoModule.getHttpClient())
                    .numWorkerThreads(2)
                    .version(version)
                    .build();
        } else {
            checkNotNull(viewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .servlet(false)
                    .offline(true)
                    .confDir(confDir)
                    .sharedConfDir(sharedConfDir)
                    .logDir(logDir)
                    .logFileNamePattern(Pattern.compile("glowroot.*\\.log"))
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(null)
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .agentRollupRepository(new AgentRollupRepositoryImpl())
                    .environmentRepository(simpleRepoModule.getEnvironmentDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .traceAttributeNameRepository(
                            simpleRepoModule.getTraceAttributeNameRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateDao())
                    .traceRepository(simpleRepoModule.getTraceDao())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueDao())
                    .syntheticResultRepository(null)
                    .incidentRepository(simpleRepoModule.getIncidentDao())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(new LiveTraceRepositoryNop())
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(null)
                    .sessionMapFactory(new SessionMapFactory() {
                        @Override
                        public <V extends /*@NonNull*/ Serializable> ConcurrentMap<String, V> create() {
                            return Maps.newConcurrentMap();
                        }
                    })
                    .httpClient(simpleRepoModule.getHttpClient())
                    .numWorkerThreads(10)
                    .version(version)
                    .build();
        }
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
            uiModule.close();
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
        // and unlock the agent directory
        agentDirsLockingCloseable.close();
    }

    private static DataSource createDataSource(boolean h2MemDb, File dataDir) throws SQLException {
        if (h2MemDb) {
            // mem db is only used for testing (by glowroot-agent-it-harness)
            return new DataSource();
        } else {
            return new DataSource(new File(dataDir, "data.h2.db"));
        }
    }

    private static boolean needToAddAlertPermission(DataSource dataSource) throws SQLException {
        if (dataSource.tableExists("trace")) {
            // new database, not an upgrade
            return false;
        }
        if (!dataSource.tableExists("triggered_alert")) {
            // upgrade from database created _after_ TriggeredAlertDao was removed
            return true;
        }
        if (dataSource.columnExists("triggered_alert", "alert_config_version")) {
            // upgrade from database created _before_ TriggeredAlertDao was removed
            return true;
        }
        return false;
    }

    private static void addAlertPermission(ConfigRepositoryImpl configRepository) throws Exception {
        for (RoleConfig config : configRepository.getRoleConfigs()) {
            if (config.isPermitted(SimplePermission.create("agent:transaction:overview"))
                    || config.isPermitted(SimplePermission.create("agent:error:overview"))
                    || config.isPermitted(SimplePermission.create("agent:jvm:gauges"))) {
                ImmutableRoleConfig updatedConfig = ImmutableRoleConfig.builder()
                        .copyFrom(config)
                        .addPermissions("agent:alert")
                        .build();
                configRepository.updateRoleConfig(updatedConfig, Versions.getJsonVersion(config));
            }
        }
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

    private static class AgentRollupRepositoryImpl implements AgentRollupRepository {

        @Override
        public List<AgentRollup> readRecentlyActiveAgentRollups(int lastXDays) {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .lastDisplayPart("")
                    .build());
        }

        @Override
        public List<AgentRollup> readAgentRollups(long from, long to) {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .lastDisplayPart("")
                    .build());
        }

        @Override
        public String readAgentRollupDisplay(String agentRollupId) {
            return "";
        }

        @Override
        public List<String> readAgentRollupDisplayParts(String agentRollupId) {
            return ImmutableList.of("");
        }
    }
}
