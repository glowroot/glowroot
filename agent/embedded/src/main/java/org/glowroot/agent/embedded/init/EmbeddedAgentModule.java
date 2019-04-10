/*
 * Copyright 2013-2019 the original author or authors.
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

import java.io.File;
import java.io.Serializable;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.embedded.repo.AgentDisplayRepositoryImpl;
import org.glowroot.agent.embedded.repo.ConfigRepositoryImpl;
import org.glowroot.agent.embedded.repo.PlatformMBeanServerLifecycle;
import org.glowroot.agent.embedded.repo.SimpleRepoModule;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.CollectorProxy;
import org.glowroot.agent.init.EnvironmentCreator;
import org.glowroot.agent.init.JRebelWorkaround;
import org.glowroot.agent.init.PreCheckLoadedClasses.PreCheckClassFileTransformer;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.RoleConfig.SimplePermission;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.ImmutableAgentRollup;
import org.glowroot.common2.repo.ImmutableTopLevelAgentRollup;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.SessionMapFactory;
import org.glowroot.ui.UiModule;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class EmbeddedAgentModule {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // only look at system property, b/c there doesn't seem any point in setting agent.port in
    // the glowroot.properties file, since in that case better to set it in the config.json file
    private static final @Nullable Integer WEB_PORT_OVERRIDE =
            Integer.getInteger("glowroot.agent.port");

    private final File logDir;
    private final List<File> confDirs;
    private final Ticker ticker;
    private final Clock clock;

    private final PluginCache pluginCache;

    private final @Nullable AgentModule agentModule;
    private final @Nullable OfflineViewerAgentModule offlineViewerAgentModule;

    private final String version;

    private volatile @MonotonicNonNull ScheduledExecutorService backgroundExecutor;
    private volatile @MonotonicNonNull SimpleRepoModule simpleRepoModule;

    private volatile @MonotonicNonNull UiModule uiModule;

    private final CountDownLatch simpleRepoModuleInit = new CountDownLatch(1);

    EmbeddedAgentModule(@Nullable File pluginsDir, List<File> confDirs, boolean configReadOnly,
            File logDir, File tmpDir, @Nullable Instrumentation instrumentation,
            @Nullable PreCheckClassFileTransformer preCheckClassFileTransformer,
            @Nullable File glowrootJarFile, String glowrootVersion, boolean offlineViewer)
            throws Exception {

        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();

        // need to perform jrebel workaround prior to loading any jackson classes
        JRebelWorkaround.perform();
        pluginCache = PluginCache.create(pluginsDir, false);
        if (offlineViewer) {
            agentModule = null;
            offlineViewerAgentModule =
                    new OfflineViewerAgentModule(pluginsDir, confDirs, configReadOnly);
        } else {
            // agent module needs to be started as early as possible, so that weaving will be
            // applied to as many classes as possible
            // in particular, it needs to be started before SimpleRepoModule which uses shaded H2,
            // which loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
            // services/java.sql.Driver, and those drivers need to be woven
            ConfigService configService =
                    ConfigService.create(confDirs, configReadOnly, pluginCache.pluginDescriptors());
            agentModule = new AgentModule(clock, null, pluginCache, configService, instrumentation,
                    glowrootJarFile, tmpDir, preCheckClassFileTransformer);
            offlineViewerAgentModule = null;
        }
        this.confDirs = confDirs;
        this.logDir = logDir;
        this.version = glowrootVersion;
    }

    void setOnEnteringMain(OnEnteringMain onEnteringMain) {
        checkNotNull(agentModule);
        agentModule.setOnEnteringMain(onEnteringMain);
    }

    void onEnteringMain(final List<File> confDirs, boolean configReadOnly, final File dataDir,
            @Nullable File glowrootJarFile, Map<String, String> properties,
            @Nullable Instrumentation instrumentation,
            final @Nullable Class<? extends Collector> collectorProxyClass,
            final String glowrootVersion, @Nullable String mainClass) throws Exception {

        PreInitializeStorageShutdownClasses.preInitializeClasses();
        // mem db is only used for testing (by glowroot-agent-it-harness)
        final boolean h2MemDb = Boolean.parseBoolean(properties.get("glowroot.internal.h2.memdb"));
        if (agentModule == null) {
            checkNotNull(offlineViewerAgentModule);
            ConfigRepositoryImpl configRepository =
                    new ConfigRepositoryImpl(confDirs, configReadOnly, WEB_PORT_OVERRIDE,
                            offlineViewerAgentModule.getConfigService(), pluginCache);
            DataSource dataSource = createDataSource(h2MemDb, dataDir);
            simpleRepoModule = new SimpleRepoModule(dataSource, dataDir, clock, ticker,
                    configRepository, null);
            simpleRepoModuleInit.countDown();
        } else {
            backgroundExecutor = Executors.newScheduledThreadPool(2,
                    ThreadFactories.create("Glowroot-Background-%d"));
            final CollectorProxy collectorProxy = new CollectorProxy();
            agentModule.onEnteringMain(backgroundExecutor, collectorProxy, instrumentation,
                    glowrootJarFile, mainClass);

            final ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(confDirs,
                    configReadOnly, WEB_PORT_OVERRIDE, agentModule.getConfigService(), pluginCache);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // TODO report checker framework issue that occurs without checkNotNull
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
                        Collector collector = new EmbeddedCollector(
                                simpleRepoModule.getEnvironmentDao(),
                                simpleRepoModule.getAggregateDao(), simpleRepoModule.getTraceDao(),
                                simpleRepoModule.getGaugeValueDao(), configRepository,
                                simpleRepoModule.getAlertingService(),
                                simpleRepoModule.getAlertingDisabledDao(),
                                simpleRepoModule.getHttpClient(), clock);
                        if (collectorProxyClass != null) {
                            startupLogger.info("using collector proxy: {}",
                                    collectorProxyClass.getName());
                            collector = collectorProxyClass.getConstructor(Collector.class)
                                    .newInstance(collector);
                        }
                        collectorProxy.setInstance(collector);
                        // embedded collector does nothing with agent config parameter
                        collector.init(confDirs,
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
                        simpleRepoModuleInit.countDown();
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
        }
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
                    .offlineViewer(false)
                    .webPortReadOnly(WEB_PORT_OVERRIDE != null)
                    .confDirs(confDirs)
                    .logDir(logDir)
                    .logFileNamePattern(Pattern.compile("glowroot.*\\.log"))
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .agentDisplayRepository(new AgentDisplayRepositoryImpl())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .alertingDisabledRepository(simpleRepoModule.getAlertingDisabledDao())
                    .activeAgentRepository(new ActiveAgentRepositoryImpl())
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
            checkNotNull(offlineViewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .servlet(false)
                    .offlineViewer(true)
                    .webPortReadOnly(WEB_PORT_OVERRIDE != null)
                    .confDirs(confDirs)
                    .logDir(logDir)
                    .logFileNamePattern(Pattern.compile("glowroot.*\\.log"))
                    .ticker(ticker)
                    .clock(clock)
                    .liveJvmService(null)
                    .agentDisplayRepository(new AgentDisplayRepositoryImpl())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .alertingDisabledRepository(simpleRepoModule.getAlertingDisabledDao())
                    .activeAgentRepository(new ActiveAgentRepositoryImpl())
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
            MILLISECONDS.sleep(10);
        }
        throw new IllegalStateException("UI Module failed to start");
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (uiModule != null) {
            uiModule.close(false);
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
        // upgrade from database created _before_ TriggeredAlertDao was removed
        return dataSource.columnExists("triggered_alert", "alert_config_version");
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

    private static class ActiveAgentRepositoryImpl implements ActiveAgentRepository {

        @Override
        public List<TopLevelAgentRollup> readActiveTopLevelAgentRollups(long from, long to) {
            return ImmutableList.<TopLevelAgentRollup>of(ImmutableTopLevelAgentRollup.builder()
                    .id("")
                    .display("")
                    .build());
        }

        @Override
        public List<AgentRollup> readActiveChildAgentRollups(String topLevelId, long from,
                long to) {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .lastDisplayPart("")
                    .build());
        }

        @Override
        public List<AgentRollup> readRecentlyActiveAgentRollups(long lastXMillis) {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .lastDisplayPart("")
                    .build());
        }

        @Override
        public List<AgentRollup> readActiveAgentRollups(long from, long to) {
            return ImmutableList.<AgentRollup>of(ImmutableAgentRollup.builder()
                    .id("")
                    .display("")
                    .lastDisplayPart("")
                    .build());
        }
    }
}
