/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.central;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;

import ch.qos.logback.classic.LoggerContext;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.TimestampGenerator;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import com.datastax.driver.core.policies.Policies;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.RateLimiter;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.central.repo.CentralRepoModule;
import org.glowroot.central.repo.ConfigRepositoryImpl.AgentConfigListener;
import org.glowroot.central.repo.ConfigRepositoryImpl.LazySecretKeyImpl;
import org.glowroot.central.repo.RepoAdminImpl;
import org.glowroot.central.repo.SchemaUpgrade;
import org.glowroot.central.repo.Tools;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;
import org.glowroot.common2.repo.PasswordHash;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.AlertingService.IncidentKey;
import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.common2.repo.util.HttpClient;
import org.glowroot.common2.repo.util.LazySecretKey;
import org.glowroot.common2.repo.util.LockSet;
import org.glowroot.common2.repo.util.MailService;
import org.glowroot.ui.CommonHandler;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.SessionMapFactory;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class CentralModule {

    private static final int TARGET_MAX_ACTIVE_AGENTS_IN_PAST_7_DAYS = 10000;
    private static final int TARGET_MAX_CENTRAL_UI_USERS = 100;

    // need to wait to init logger until after establishing centralDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    private final boolean servlet;
    private final ClusterManager clusterManager;
    private final Cluster cluster;
    private final Session session;
    private final ExecutorService repoAsyncExecutor;
    private final CentralRepoModule repos;
    private final AlertingService alertingService;
    private final CentralAlertingService centralAlertingService;
    private final GrpcServer grpcServer;
    private final UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService;
    private final RollupService rollupService;
    private final SyntheticMonitorService syntheticMonitorService;
    private final UiModule uiModule;

    public static CentralModule create() throws Exception {
        return create(getCentralDir());
    }

    @VisibleForTesting
    public static CentralModule create(File centralDir) throws Exception {
        return new CentralModule(centralDir, null);
    }

    static CentralModule createForServletContainer(File centralDir, ServletContext servletContext)
            throws Exception {
        return new CentralModule(centralDir, servletContext);
    }

    private CentralModule(File centralDir, @Nullable ServletContext servletContext)
            throws Exception {
        servlet = servletContext != null;
        ClusterManager clusterManager = null;
        Cluster cluster = null;
        Session session = null;
        ExecutorService repoAsyncExecutor = null;
        CentralRepoModule repos = null;
        AlertingService alertingService = null;
        CentralAlertingService centralAlertingService = null;
        GrpcServer grpcServer = null;
        UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService = null;
        RollupService rollupService = null;
        SyntheticMonitorService syntheticMonitorService = null;
        UiModule uiModule = null;
        try {
            Directories directories = new Directories(centralDir);
            // init logger as early as possible
            initLogging(directories.getConfDir(), directories.getLogDir());
            Clock clock = Clock.systemClock();
            Ticker ticker = Ticker.systemTicker();
            String version;
            if (servletContext == null) {
                version = Version.getVersion(CentralModule.class);
            } else {
                version = Version.getVersion(servletContext.getResource("/META-INF/MANIFEST.MF"));
            }
            startupLogger.info("Glowroot version: {}", version);
            startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());
            if (servlet) {
                String extra = "";
                if (Strings.isNullOrEmpty(System.getProperty("glowroot.central.dir"))) {
                    extra = ", this can be changed by adding the JVM arg -Dglowroot.central.dir=..."
                            + " to your servlet container startup";
                }
                startupLogger.info("Glowroot home: {}{}", centralDir.getAbsolutePath(), extra);
            }

            CentralConfiguration centralConfig = getCentralConfiguration(directories.getConfDir());
            clusterManager = ClusterManager.create(directories.getConfDir(),
                    centralConfig.jgroupsProperties());
            session = connect(centralConfig);
            cluster = session.getCluster();

            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, clock, servlet);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating glowroot central schema...");
            } else {
                schemaUpgrade.upgrade();
            }
            if (schemaUpgrade.reloadCentralConfiguration()) {
                centralConfig = getCentralConfiguration(directories.getConfDir());
            }
            repoAsyncExecutor = MoreExecutors2.newCachedThreadPool("Repo-Async-Worker-%d");
            repos = new CentralRepoModule(clusterManager, session, directories.getConfDir(),
                    centralConfig.cassandraSymmetricEncryptionKey(), repoAsyncExecutor,
                    TARGET_MAX_ACTIVE_AGENTS_IN_PAST_7_DAYS, TARGET_MAX_CENTRAL_UI_USERS, clock);

            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("glowroot central schema created");
            } else {
                schemaUpgrade.updateToMoreRecentCassandraOptions(
                        repos.getConfigRepository().getCentralStorageConfig());
            }

            HttpClient httpClient = new HttpClient(repos.getConfigRepository());
            LockSet<IncidentKey> openingIncidentLockSet =
                    clusterManager.createReplicatedLockSet("openingIncidentLockSet", 60, SECONDS);
            LockSet<IncidentKey> resolvingIncidentLockSet =
                    clusterManager.createReplicatedLockSet("resolvingIncidentLockSet", 60, SECONDS);
            alertingService = new AlertingService(repos.getConfigRepository(),
                    repos.getIncidentDao(), repos.getAggregateDao(), repos.getGaugeValueDao(),
                    repos.getTraceDao(), repos.getRollupLevelService(), new MailService(),
                    httpClient, openingIncidentLockSet, resolvingIncidentLockSet, clock);
            HeartbeatAlertingService heartbeatAlertingService = new HeartbeatAlertingService(
                    repos.getHeartbeatDao(), repos.getIncidentDao(), alertingService,
                    repos.getConfigRepository());
            centralAlertingService = new CentralAlertingService(repos.getConfigRepository(),
                    alertingService, heartbeatAlertingService, repos.getAlertingDisabledDao(),
                    clock);

            grpcServer = new GrpcServer(centralConfig.grpcBindAddress(),
                    centralConfig.grpcHttpPort(), centralConfig.grpcHttpsPort(),
                    directories.getConfDir(), repos.getAgentDisplayDao(), repos.getAgentConfigDao(),
                    repos.getActiveAgentDao(), repos.getEnvironmentDao(), repos.getHeartbeatDao(),
                    repos.getAggregateDao(), repos.getGaugeValueDao(), repos.getTraceDao(),
                    repos.getV09AgentRollupDao(), centralAlertingService, clusterManager, clock,
                    version);
            DownstreamServiceImpl downstreamService = grpcServer.getDownstreamService();
            updateAgentConfigIfNeededService = new UpdateAgentConfigIfNeededService(
                    repos.getAgentConfigDao(), repos.getActiveAgentDao(), downstreamService, clock);
            UpdateAgentConfigIfNeededService updateAgentConfigIfNeededServiceEffectivelyFinal =
                    updateAgentConfigIfNeededService;
            repos.getConfigRepository().addAgentConfigListener(new AgentConfigListener() {
                @Override
                public void onChange(String agentId) throws Exception {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(updateAgentConfigIfNeededServiceEffectivelyFinal)
                            .updateAgentConfigIfNeededAndConnectedAsync(agentId);
                }
            });
            rollupService = new RollupService(repos.getActiveAgentDao(), repos.getAggregateDao(),
                    repos.getGaugeValueDao(), repos.getSyntheticResultDao(), centralAlertingService,
                    clock);
            syntheticMonitorService = new SyntheticMonitorService(repos.getActiveAgentDao(),
                    repos.getConfigRepository(), repos.getAlertingDisabledDao(),
                    repos.getIncidentDao(), alertingService, repos.getSyntheticResultDao(),
                    clusterManager, ticker, clock, version);

            ClusterManager clusterManagerEffectivelyFinal = clusterManager;
            uiModule = new CreateUiModuleBuilder()
                    .central(true)
                    .servlet(servlet)
                    .offlineViewer(false)
                    .webPortReadOnly(false) // this only applies to embedded ui
                    .bindAddress(centralConfig.uiBindAddress())
                    .port(centralConfig.uiPort())
                    .https(centralConfig.uiHttps())
                    .contextPath(centralConfig.uiContextPath())
                    .confDirs(Arrays.asList(directories.getConfDir()))
                    .logDir(directories.getLogDir())
                    .logFileNamePattern(Pattern.compile("glowroot-central.*\\.log"))
                    .clock(clock)
                    .liveJvmService(new LiveJvmServiceImpl(downstreamService))
                    .agentDisplayRepository(repos.getAgentDisplayDao())
                    .configRepository(repos.getConfigRepository())
                    .alertingDisabledRepository(repos.getAlertingDisabledDao())
                    .activeAgentRepository(repos.getActiveAgentDao())
                    .environmentRepository(repos.getEnvironmentDao())
                    .transactionTypeRepository(repos.getTransactionTypeDao())
                    .traceAttributeNameRepository(repos.getTraceAttributeNameDao())
                    .traceRepository(repos.getTraceDao())
                    .aggregateRepository(repos.getAggregateDao())
                    .gaugeValueRepository(repos.getGaugeValueDao())
                    .syntheticResultRepository(repos.getSyntheticResultDao())
                    .incidentRepository(repos.getIncidentDao())
                    .repoAdmin(new RepoAdminImpl(session, repos.getActiveAgentDao(),
                            repos.getConfigRepository(), session.getCassandraWriteMetrics(), clock))
                    .rollupLevelService(repos.getRollupLevelService())
                    .liveTraceRepository(new LiveTraceRepositoryImpl(downstreamService))
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceImpl(downstreamService))
                    .sessionMapFactory(new SessionMapFactory() {
                        @Override
                        public <V extends /*@NonNull*/ Serializable> ConcurrentMap<String, V> create() {
                            return clusterManagerEffectivelyFinal.createReplicatedMap("sessionMap");
                        }
                    })
                    .httpClient(httpClient)
                    .numWorkerThreads(50)
                    .version(version)
                    .build();
            startupLogger.info("startup complete");
        } catch (Throwable t) {
            if (startupLogger == null) {
                t.printStackTrace();
            } else {
                startupLogger.error(t.getMessage(), t);
            }
            // try to shut down cleanly, otherwise apache commons daemon (via Procrun) doesn't
            // know service failed to start up
            if (uiModule != null) {
                uiModule.close(false);
            }
            if (syntheticMonitorService != null) {
                syntheticMonitorService.close();
            }
            if (rollupService != null) {
                rollupService.close();
            }
            if (updateAgentConfigIfNeededService != null) {
                updateAgentConfigIfNeededService.close();
            }
            if (grpcServer != null) {
                grpcServer.close(false);
            }
            if (centralAlertingService != null) {
                centralAlertingService.close();
            }
            if (alertingService != null) {
                alertingService.close();
            }
            if (repos != null) {
                repos.close();
            }
            if (repoAsyncExecutor != null) {
                repoAsyncExecutor.shutdown();
            }
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
            if (clusterManager != null) {
                clusterManager.close();
            }
            throw t;
        }
        this.clusterManager = clusterManager;
        this.cluster = cluster;
        this.session = session;
        this.repoAsyncExecutor = repoAsyncExecutor;
        this.repos = repos;
        this.alertingService = alertingService;
        this.centralAlertingService = centralAlertingService;
        this.grpcServer = grpcServer;
        this.updateAgentConfigIfNeededService = updateAgentConfigIfNeededService;
        this.rollupService = rollupService;
        this.syntheticMonitorService = syntheticMonitorService;
        this.uiModule = uiModule;
    }

    CommonHandler getCommonHandler() {
        return uiModule.getCommonHandler();
    }

    public void shutdown(boolean jvmTermination) {
        if (startupLogger != null) {
            startupLogger.info("shutting down...");
        }
        try {
            ExecutorService executor = Executors.newCachedThreadPool();
            List<Future<?>> futures = new ArrayList<>();
            // gracefully close down external inputs first (ui and grpc)
            futures.add(submit(executor, () -> uiModule.close(jvmTermination)));
            // updateAgentConfigIfNeededService depends on grpc downstream, so must be shutdown
            // before grpc
            futures.add(submit(executor, updateAgentConfigIfNeededService::close));
            futures.add(submit(executor, () -> grpcServer.close(jvmTermination)));

            if (jvmTermination) {
                // nothing else needs orderly shutdown when JVM is being terminated
                MoreFutures.waitForAll(futures);
                if (startupLogger != null) {
                    startupLogger.info("shutdown complete");
                }
                return;
            }

            // forcefully close down background tasks
            submit(executor, syntheticMonitorService::close);
            submit(executor, rollupService::close);
            submit(executor, centralAlertingService::close);
            submit(executor, alertingService::close);
            submit(executor, repos::close);

            MoreFutures.waitForAll(futures);

            repoAsyncExecutor.shutdown();
            session.close();
            cluster.close();
            clusterManager.close();
            if (startupLogger != null) {
                startupLogger.info("shutdown complete");
                if (!servlet) {
                    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces()
                            .entrySet()) {
                        Thread thread = entry.getKey();
                        StackTraceElement[] stackTrace = entry.getValue();
                        if (!thread.isDaemon() && thread != Thread.currentThread()
                                && stackTrace.length != 0) {
                            startupLogger.info("Found non-daemon thread after shutdown: {}\n    {}",
                                    thread.getName(), Joiner.on("\n    ").join(stackTrace));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (startupLogger == null) {
                t.printStackTrace();
            } else {
                startupLogger.error("error during shutdown: {}", t.getMessage(), t);
            }
        } finally {
            // need to explicitly stop because disabling normal registration in
            // LogbackServletContainerInitializer via web.xml context-param
            //
            // there is some precedent to stopping even if jvmTermination, see
            // org.springframework.boot.logging.logback.LogbackLoggingSystem.ShutdownHandler
            ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
            if (loggerFactory instanceof LoggerContext) {
                ((LoggerContext) loggerFactory).stop();
            }
        }
    }

    static void createSchema() throws Exception {
        File centralDir = getCentralDir();
        Directories directories = new Directories(centralDir);
        initLogging(directories.getConfDir(), directories.getLogDir());
        String version = Version.getVersion(CentralModule.class);
        startupLogger.info("running create-schema command");
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());

        CentralConfiguration centralConfig = getCentralConfiguration(directories.getConfDir());
        Session session = null;
        Cluster cluster = null;
        ExecutorService repoAsyncExecutor = null;
        CentralRepoModule repos = null;
        try {
            session = connect(centralConfig);
            cluster = session.getCluster();
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, Clock.systemClock(), false);
            if (schemaUpgrade.getInitialSchemaVersion() != null) {
                startupLogger.error("glowroot central schema already exists, exiting");
                return;
            }
            startupLogger.info("creating glowroot central schema...");
            repoAsyncExecutor = MoreExecutors2.newCachedThreadPool("Repo-Async-Worker-%d");
            repos = new CentralRepoModule(ClusterManager.create(), session, centralDir,
                    centralConfig.cassandraSymmetricEncryptionKey(), repoAsyncExecutor, 10, 10,
                    Clock.systemClock());
            schemaUpgrade.updateSchemaVersionToCurent();
        } finally {
            if (repos != null) {
                repos.close();
            }
            if (repoAsyncExecutor != null) {
                repoAsyncExecutor.shutdown();
            }
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
        }
        startupLogger.info("glowroot central schema created");
    }

    static void runCommand(String commandName, List<String> args) throws Exception {
        File centralDir = getCentralDir();
        Directories directories = new Directories(centralDir);

        Command command;
        if (commandName.equals("setup-admin-user")) {
            if (args.size() != 2) {
                System.err.println(
                        "setup-admin-user requires two args (username and password), exiting");
                return;
            }
            command = Tools::setupAdminUser;
        } else if (commandName.equals("hash-password")) {
            if (args.size() != 1) {
                System.err.println("hash-password requires one arg (plain password), exiting");
                return;
            }
            String plainPassword = args.get(0);
            System.out.println(PasswordHash.createHash(plainPassword));
            return;
        } else if (commandName.equals("encrypt-password")) {
            if (args.size() != 1) {
                System.err.println("encrypt-password requires one args (plain password), exiting");
                return;
            }
            String plainPassword = args.get(0);
            CentralConfiguration centralConfig = getCentralConfiguration(directories.getConfDir());
            String symmetricEncryptionKey = centralConfig.cassandraSymmetricEncryptionKey();
            if (symmetricEncryptionKey.isEmpty()) {
                System.err.println("cassandra.symmetricEncryptionKey must be configured in the"
                        + " glowroot-central.properties file before passwords can be encrypted");
                return;
            }
            LazySecretKey lazySecretKey = new LazySecretKeyImpl(symmetricEncryptionKey);
            System.out.println(Encryption.encrypt(plainPassword, lazySecretKey));
            return;
        } else if (commandName.equals("truncate-all-data")) {
            if (!args.isEmpty()) {
                System.err.println("truncate-all-data does not accept any args, exiting");
                return;
            }
            command = Tools::truncateAllData;
        } else if (commandName.equals("delete-old-data")
                || commandName.equals("delete-bad-future-data")) {
            if (args.size() != 2) {
                System.err.format(
                        "%s requires two args (partial table name and rollup level), exiting%n",
                        commandName);
                return;
            }
            String partialTableName = args.get(0);
            if (!partialTableName.equals("query")
                    && !partialTableName.equals("service_call")
                    && !partialTableName.equals("profile")
                    && !partialTableName.equals("overview")
                    && !partialTableName.equals("histogram")
                    && !partialTableName.equals("throughput")
                    && !partialTableName.equals("summary")
                    && !partialTableName.equals("error_summary")
                    && !partialTableName.equals("gauge_value")) {
                System.err.println("partial table name must be one of \"query\", \"service_call\","
                        + " \"profile\", \"overview\", \"histogram\", \"throughput\", \"summary\","
                        + " \"error_summary\" or \"gauge_value\", exiting");
                return;
            }
            if (commandName.equals("delete-old-data")) {
                command = Tools::deleteOldData;
            } else if (commandName.equals("delete-bad-future-data")) {
                command = Tools::deleteBadFutureData;
            } else {
                System.err.format("unexpected command '%s', exiting%n", commandName);
                return;
            }
        } else {
            System.err.format("unexpected command '%s', exiting%n", commandName);
            return;
        }

        initLogging(directories.getConfDir(), directories.getLogDir());

        String version = Version.getVersion(CentralModule.class);
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());

        CentralConfiguration centralConfig = getCentralConfiguration(directories.getConfDir());
        Session session = null;
        Cluster cluster = null;
        ExecutorService repoAsyncExecutor = null;
        CentralRepoModule repos = null;
        boolean success;
        try {
            session = connect(centralConfig);
            cluster = session.getCluster();
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, Clock.systemClock(), false);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating glowroot central schema...");
            } else if (initialSchemaVersion != schemaUpgrade.getCurrentSchemaVersion()) {
                startupLogger.warn("running a version of glowroot central that does not match the"
                        + " glowroot central schema version (expecting glowroot central schema"
                        + " version {} but found version {}), exiting",
                        schemaUpgrade.getCurrentSchemaVersion(), initialSchemaVersion);
                return;
            }
            repoAsyncExecutor = MoreExecutors2.newCachedThreadPool("Repo-Async-Worker-%d");
            repos = new CentralRepoModule(ClusterManager.create(), session, centralDir,
                    centralConfig.cassandraSymmetricEncryptionKey(), repoAsyncExecutor, 10, 10,
                    Clock.systemClock());
            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("glowroot central schema created");
            }
            startupLogger.info("running {}", commandName);
            success = command.run(new Tools(session, repos), args);
        } finally {
            if (repos != null) {
                repos.close();
            }
            if (repoAsyncExecutor != null) {
                repoAsyncExecutor.shutdown();
            }
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
        }
        if (success) {
            startupLogger.info("{} completed successfully", commandName);
        }
    }

    private static CentralConfiguration getCentralConfiguration(File confDir) throws IOException {
        Map<String, String> properties = getPropertiesFromConfigFile(confDir);
        properties = overlayAnySystemProperties(properties);
        ImmutableCentralConfiguration.Builder builder = ImmutableCentralConfiguration.builder();
        String cassandraContactPoints = properties.get("glowroot.cassandra.contactPoints");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraPortText = properties.get("glowroot.cassandra.port");
        if (!Strings.isNullOrEmpty(cassandraPortText)) {
        	builder.cassandraPort(Integer.parseInt(cassandraPortText));
        }
        String cassandraUsername = properties.get("glowroot.cassandra.username");
        if (!Strings.isNullOrEmpty(cassandraUsername)) {
            builder.cassandraUsername(cassandraUsername);
        }
        String cassandraPassword = properties.get("glowroot.cassandra.password");
        if (!Strings.isNullOrEmpty(cassandraPassword)) {
            builder.cassandraPassword(cassandraPassword);
        }
        String cassandraSSLText = properties.get("glowroot.cassandra.ssl");
        if (!Strings.isNullOrEmpty(cassandraSSLText)) {
        	builder.cassandraSSL(Boolean.parseBoolean(cassandraSSLText));
        }
        String cassandraKeyspace = properties.get("glowroot.cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
        }
        String cassandraConsistencyLevel = properties.get("glowroot.cassandra.consistencyLevel");
        if (!Strings.isNullOrEmpty(cassandraConsistencyLevel)) {
            int index = cassandraConsistencyLevel.indexOf('/');
            if (index == -1) {
                ConsistencyLevel consistencyLevel =
                        ConsistencyLevel.valueOf(cassandraConsistencyLevel);
                builder.cassandraReadConsistencyLevel(consistencyLevel);
                builder.cassandraWriteConsistencyLevel(consistencyLevel);
            } else {
                builder.cassandraReadConsistencyLevel(
                        ConsistencyLevel.valueOf(cassandraConsistencyLevel.substring(0, index)));
                builder.cassandraWriteConsistencyLevel(
                        ConsistencyLevel.valueOf(cassandraConsistencyLevel.substring(index + 1)));
            }
        }
        String cassandraSymmetricEncryptionKey =
                properties.get("glowroot.cassandra.symmetricEncryptionKey");
        if (!Strings.isNullOrEmpty(cassandraSymmetricEncryptionKey)) {
            if (!cassandraSymmetricEncryptionKey.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalStateException("Invalid cassandra.symmetricEncryptionKey value,"
                        + " it must be a 32 character hex string");
            }
            builder.cassandraSymmetricEncryptionKey(cassandraSymmetricEncryptionKey);
        }
        String cassandraMaxConcurrentQueries =
                properties.get("glowroot.cassandra.maxConcurrentQueries");
        if (!Strings.isNullOrEmpty(cassandraMaxConcurrentQueries)) {
            builder.cassandraMaxConcurrentQueries(Integer.parseInt(cassandraMaxConcurrentQueries));
        }
        String cassandraPoolTimeoutMillis = properties.get("glowroot.cassandra.pool.timeoutMillis");
        if (!Strings.isNullOrEmpty(cassandraPoolTimeoutMillis)) {
            builder.cassandraPoolTimeoutMillis(Integer.parseInt(cassandraPoolTimeoutMillis));
        }
        String grpcBindAddress = properties.get("glowroot.grpc.bindAddress");
        if (!Strings.isNullOrEmpty(grpcBindAddress)) {
            builder.grpcBindAddress(grpcBindAddress);
        }
        String grpcHttpPortText = properties.get("glowroot.grpc.httpPort");
        if (!Strings.isNullOrEmpty(grpcHttpPortText)) {
            if (grpcHttpPortText.trim().equalsIgnoreCase("none")) {
                builder.grpcHttpPort(null);
            } else {
                builder.grpcHttpPort(Integer.parseInt(grpcHttpPortText));
            }
        }
        String grpcHttpsPortText = properties.get("glowroot.grpc.httpsPort");
        if (!Strings.isNullOrEmpty(grpcHttpsPortText)) {
            if (grpcHttpsPortText.trim().equalsIgnoreCase("none")) {
                builder.grpcHttpsPort(null);
            } else {
                builder.grpcHttpsPort(Integer.parseInt(grpcHttpsPortText));
            }
        }
        String uiBindAddress = properties.get("glowroot.ui.bindAddress");
        if (!Strings.isNullOrEmpty(uiBindAddress)) {
            builder.uiBindAddress(uiBindAddress);
        }
        String uiPortText = properties.get("glowroot.ui.port");
        if (!Strings.isNullOrEmpty(uiPortText)) {
            builder.uiPort(Integer.parseInt(uiPortText));
        }
        String uiHttpsText = properties.get("glowroot.ui.https");
        if (!Strings.isNullOrEmpty(uiHttpsText)) {
            builder.uiHttps(Boolean.parseBoolean(uiHttpsText));
        }
        String uiContextPath = properties.get("glowroot.ui.contextPath");
        if (!Strings.isNullOrEmpty(uiContextPath)) {
            builder.uiContextPath(uiContextPath);
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
            if (propertyName.startsWith("glowroot.jgroups.")) {
                String propertyValue = entry.getValue();
                if (!Strings.isNullOrEmpty(propertyValue)) {
                    builder.putJgroupsProperties(propertyName.substring("glowroot.".length()),
                            propertyValue);
                }
            }
        }
        return builder.build();
    }

    private static Map<String, String> getPropertiesFromConfigFile(File confDir)
            throws IOException {
        File propFile = new File(confDir, "glowroot-central.properties");
        if (!propFile.exists()) {
            // upgrade from 0.9.5 to 0.9.6
            File oldPropFile = new File(confDir, "glowroot-server.properties");
            if (!oldPropFile.exists()) {
                return ImmutableMap.of();
            }
            Files.copy(oldPropFile.toPath(), propFile.toPath());
        }
        Properties props = PropertiesFiles.load(propFile);

        Map<String, String> upgradePropertyNames = new HashMap<>();
        // upgrade from 0.9.4 to 0.9.5
        if (props.containsKey("cassandra.contact.points")) {
            upgradePropertyNames.put("cassandra.contact.points=", "cassandra.contactPoints=");
        }
        // upgrade from 0.9.15 and 0.9.16-SNAPSHOT (tcp early release) to 0.9.16
        String jgroupsConfigurationFile = props.getProperty("jgroups.configurationFile");
        if (("default-jgroups-udp.xml".equals(jgroupsConfigurationFile)
                || "default-jgroups-tcp.xml".equals(jgroupsConfigurationFile))
                && !new File(confDir, jgroupsConfigurationFile).exists()) {
            // using one of the included jgroups xml files prior to 0.9.16
            upgradePropertyNames.put("jgroups.configurationFile=default-jgroups-udp.xml",
                    "jgroups.configurationFile=jgroups-udp.xml");
            upgradePropertyNames.put("jgroups.configurationFile=default-jgroups-tcp.xml",
                    "jgroups.configurationFile=jgroups-tcp.xml");
            upgradePropertyNames.put("jgroups.udp.mcast_addr=", "jgroups.multicastAddress=");
            upgradePropertyNames.put("jgroups.udp.mcast_port=", "jgroups.multicastPort=");
            upgradePropertyNames.put("jgroups.thread_pool.min_threads=", "jgroups.minThreads=");
            upgradePropertyNames.put("jgroups.thread_pool.max_threads=", "jgroups.maxThreads=");
            upgradePropertyNames.put("jgroups.ip_ttl=", "jgroups.multicastTTL=");
            upgradePropertyNames.put("jgroups.join_timeout=", "jgroups.joinTimeout=");
            upgradePropertyNames.put("jgroups.tcp.address=", "jgroups.localAddress=");
            upgradePropertyNames.put("jgroups.tcp.port=", "jgroups.localPort=");
            String initialHosts = props.getProperty("jgroups.tcp.initial_hosts");
            if (initialHosts != null) {
                // transform from "host1[port1],host2[port2],..." to "host1:port1,host2:port2,..."
                String initialNodes =
                        Pattern.compile("\\[([0-9]+)\\]").matcher(initialHosts).replaceAll(":$1");
                upgradePropertyNames.put("jgroups.tcp.initial_hosts=" + initialHosts,
                        "jgroups.initialNodes=" + initialNodes);
            }
        }
        // upgrade from 0.12.3 to 0.13.0
        if (props.containsKey("cassandra.pool.maxRequestsPerConnection")) {
            upgradePropertyNames.put("cassandra.pool.maxRequestsPerConnection",
                    "cassandra.maxConcurrentQueries");
        }
        if (!upgradePropertyNames.isEmpty()) {
            PropertiesFiles.upgradeIfNeeded(propFile, upgradePropertyNames);
            props = PropertiesFiles.load(propFile);
        }
        // upgrade from 0.9.15 to 0.9.16
        File secretFile = new File(confDir, "secret");
        if (secretFile.exists()) {
            String existingValue = props.getProperty("cassandra.symmetricEncryptionKey");
            if (Strings.isNullOrEmpty(existingValue)) {
                byte[] bytes = Files.readAllBytes(secretFile.toPath());
                String newValue = BaseEncoding.base16().lowerCase().encode(bytes);
                if (existingValue == null) {
                    try (Writer out =
                            Files.newBufferedWriter(propFile.toPath(), UTF_8, CREATE, APPEND)) {
                        out.write("\ncassandra.symmetricEncryptionKey=");
                        out.write(newValue);
                        out.write("\n");
                    }
                } else {
                    // existingValue is ""
                    PropertiesFiles.upgradeIfNeeded(propFile,
                            ImmutableMap.of("cassandra.symmetricEncryptionKey=",
                                    "cassandra.symmetricEncryptionKey=" + newValue));
                }
                props = PropertiesFiles.load(propFile);
                if (!secretFile.delete()) {
                    throw new IOException("Could not delete secret file after moving symmetric"
                            + " encryption key to glowroot-central.properties");
                }
            }
        }
        Map<String, String> properties = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value != null) {
                properties.put("glowroot." + key, value);
            }
        }
        return properties;
    }

    private static Map<String, String> overlayAnySystemProperties(Map<String, String> props) {
        Map<String, String> properties = Maps.newHashMap(props);
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("glowroot.")) {
                String key = (String) entry.getKey();
                properties.put(key, (String) entry.getValue());
            }
        }
        return properties;
    }

    @RequiresNonNull("startupLogger")
    private static Session connect(CentralConfiguration centralConfig) throws Exception {
        Session session = null;
        // instantiate the default timestamp generator (AtomicMonotonicTimestampGenerator) only once
        // since it calls com.datastax.driver.core.ClockFactory.newInstance() via super class
        // (AbstractMonotonicTimestampGenerator) which logs whether using native clock or not,
        // which is useful, but annoying when waiting for cassandra to start up and the message gets
        // logged over and over and over
        TimestampGenerator defaultTimestampGenerator = Policies.defaultTimestampGenerator();

        RateLimitedLogger waitingForCassandraLogger = new RateLimitedLogger(CentralModule.class);
        RateLimitedLogger waitingForCassandraReplicasLogger =
                new RateLimitedLogger(CentralModule.class);

        Stopwatch stopwatch = Stopwatch.createStarted();
        NoHostAvailableException lastException = null;
        while (stopwatch.elapsed(MINUTES) < 30) {
            try {
                String keyspace = centralConfig.cassandraKeyspace();
                if (session == null) {
                    ConsistencyLevel writeConsistencyLevelOverride;
                    if (centralConfig.cassandraWriteConsistencyLevel() == centralConfig
                            .cassandraReadConsistencyLevel()) {
                        writeConsistencyLevelOverride = null;
                    } else {
                        writeConsistencyLevelOverride =
                                centralConfig.cassandraWriteConsistencyLevel();
                    }
                    session = new Session(
                            createCluster(centralConfig, defaultTimestampGenerator).connect(),
                            keyspace, writeConsistencyLevelOverride,
                            // max concurrent queries before throwing BusyPoolException is "max
                            // requests per connection" + "max queue size" (which are set to
                            // cassandraMaxConcurrentQueries and cassandraMaxConcurrentQueries * 2
                            // respectively)
                            centralConfig.cassandraMaxConcurrentQueries() * 3);
                }
                String cassandraVersion = verifyCassandraVersion(session);
                KeyspaceMetadata keyspaceMetadata =
                        checkNotNull(session.getCluster().getMetadata().getKeyspace(keyspace));
                String replicationFactor =
                        keyspaceMetadata.getReplication().get("replication_factor");
                if (replicationFactor == null) {
                    replicationFactor = "unknown";
                }
                startupLogger.info("connected to Cassandra (version {}), using keyspace '{}'"
                        + " (replication factor {}) and consistency level {}", cassandraVersion,
                        keyspace, replicationFactor,
                        centralConfig.cassandraConsistencyLevelDisplay());
                return session;
            } catch (NoHostAvailableException e) {
                startupLogger.debug(e.getMessage(), e);
                lastException = e;
                if (session == null) {
                    waitingForCassandraLogger.info("waiting for Cassandra ({})...",
                            Joiner.on(",").join(centralConfig.cassandraContactPoint()));
                } else {
                    waitingForCassandraReplicasLogger.info("waiting for enough Cassandra replicas"
                            + " to run queries at consistency level {} ({})...",
                            centralConfig.cassandraConsistencyLevelDisplay(),
                            Joiner.on(",").join(centralConfig.cassandraContactPoint()));
                }
                SECONDS.sleep(1);
            } catch (RuntimeException e) {
                // clean up
                if (session != null) {
                    session.getCluster().close();
                }
                throw e;
            }
        }
        // clean up
        if (session != null) {
            session.getCluster().close();
        }
        checkNotNull(lastException);
        throw lastException;
    }

    private static Cluster createCluster(CentralConfiguration centralConfig,
            TimestampGenerator defaultTimestampGenerator) {
        Cluster.Builder builder = Cluster.builder()
                .addContactPoints(
                        centralConfig.cassandraContactPoint().toArray(new String[0]))
                .withPort(centralConfig.cassandraPort())
                // aggressive reconnect policy seems ok since not many clients
                .withReconnectionPolicy(new ConstantReconnectionPolicy(1000))
                // let driver know that only idempotent queries are used so it will retry on timeout
                .withQueryOptions(new QueryOptions()
                        .setDefaultIdempotence(true)
                        .setConsistencyLevel(centralConfig.cassandraReadConsistencyLevel()))
                .withPoolingOptions(new PoolingOptions()
                        .setMaxRequestsPerConnection(HostDistance.LOCAL,
                                centralConfig.cassandraMaxConcurrentQueries())
                        .setMaxRequestsPerConnection(HostDistance.REMOTE,
                                centralConfig.cassandraMaxConcurrentQueries())
                        // using 2x "max requests per connection", so that thread-based
                        // throttling can allow up to 3x "max requests per connection", which is
                        // split 50% writes / 25% reads / 25% rollups, which will keep the pool
                        // saturated with writes alone
                        .setMaxQueueSize(centralConfig.cassandraMaxConcurrentQueries() * 2)
                        .setPoolTimeoutMillis(centralConfig.cassandraPoolTimeoutMillis()))
                .withTimestampGenerator(defaultTimestampGenerator);
        String cassandraUsername = centralConfig.cassandraUsername();
        if (!cassandraUsername.isEmpty()) {
            // empty password is strange but valid
            builder.withCredentials(cassandraUsername, centralConfig.cassandraPassword());
        }
        if (centralConfig.cassandraSSL()) {
        	builder.withSSL();
        }
        return builder.build();
    }

    private static String verifyCassandraVersion(Session session) throws Exception {
        ResultSet results =
                session.read("select release_version from system.local where key = 'local'");
        Row row = checkNotNull(results.one());
        String cassandraVersion = checkNotNull(row.getString(0));
        if (cassandraVersion.startsWith("2.0") || cassandraVersion.startsWith("1.")
                || cassandraVersion.startsWith("0.")) {
            throw new IllegalStateException(
                    "Glowroot central requires Cassandra 2.1+, but found: "
                            + cassandraVersion);
        }
        return cassandraVersion;
    }

    private static File getCentralDir() throws URISyntaxException {
        CodeSource codeSource = CentralModule.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            // this should only happen under test
            return new File(".");
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            File centralDir = codeSourceFile.getParentFile();
            if (centralDir == null) {
                return new File(".");
            } else {
                return centralDir;
            }
        } else {
            // this should only happen under test
            return new File(".");
        }
    }

    // TODO report checker framework issue that occurs without this suppression
    @EnsuresNonNull("startupLogger")
    @SuppressWarnings("contracts.postcondition.not.satisfied")
    private static void initLogging(File confDir, File logDir) {
        File logbackXmlOverride = new File(confDir, "logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("logback.configurationFile", logbackXmlOverride.getAbsolutePath());
        }
        String prior = System.getProperty("glowroot.log.dir");
        System.setProperty("glowroot.log.dir", logDir.getPath());
        try {
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            System.clearProperty("logback.configurationFile");
            if (prior == null) {
                System.clearProperty("glowroot.log.dir");
            } else {
                System.setProperty("glowroot.log.dir", prior);
            }
        }
        // install jul-to-slf4j bridge for guava/grpc/protobuf which log to jul
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static Future<?> submit(ExecutorService executor, ShutdownFunction fn) {
        return executor.submit(new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                fn.run();
                return null;
            }
        });
    }

    private interface ShutdownFunction {
        void run() throws Exception;
    }

    @Value.Immutable
    abstract static class CentralConfiguration {

        @Value.Default
        List<String> cassandraContactPoint() {
            return ImmutableList.of("127.0.0.1");
        }
        
        @Value.Default
        int cassandraPort() {
        	return 9042;
        }

        @Value.Default
        String cassandraUsername() {
            return "";
        }

        @Value.Default
        String cassandraPassword() {
            return "";
        }
        
        @Value.Default
        boolean cassandraSSL() {
        	return false;
        }

        @Value.Default
        String cassandraKeyspace() {
            return "glowroot";
        }

        @Value.Default
        ConsistencyLevel cassandraReadConsistencyLevel() {
            return ConsistencyLevel.QUORUM;
        }

        @Value.Default
        ConsistencyLevel cassandraWriteConsistencyLevel() {
            return ConsistencyLevel.QUORUM;
        }

        @Value.Derived
        String cassandraConsistencyLevelDisplay() {
            ConsistencyLevel readConsistencyLevel = cassandraReadConsistencyLevel();
            ConsistencyLevel writeConsistencyLevel = cassandraWriteConsistencyLevel();
            if (readConsistencyLevel == writeConsistencyLevel) {
                return readConsistencyLevel.name();
            } else {
                return readConsistencyLevel.name() + "/" + writeConsistencyLevel.name();
            }
        }

        @Value.Default
        String cassandraSymmetricEncryptionKey() {
            return "";
        }

        @Value.Default
        int cassandraMaxConcurrentQueries() {
            return 1024;
        }

        @Value.Default
        int cassandraPoolTimeoutMillis() {
            // central runs lots of parallel async queries and is very spiky since all aggregates
            // come in right after each minute marker
            return 10000;
        }

        @Value.Default
        String grpcBindAddress() {
            return "0.0.0.0";
        }

        @Value.Default
        @Nullable
        Integer grpcHttpPort() {
            return 8181;
        }

        @Value.Default
        @Nullable
        Integer grpcHttpsPort() {
            return null;
        }

        @Value.Default
        String uiBindAddress() {
            return "0.0.0.0";
        }

        @Value.Default
        int uiPort() {
            return 4000;
        }

        @Value.Default
        boolean uiHttps() {
            return false;
        }

        @Value.Default
        String uiContextPath() {
            return "/";
        }

        abstract Map<String, String> jgroupsProperties();
    }

    private static class RateLimitedLogger {

        private final Logger logger;

        private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);

        private RateLimitedLogger(Class<?> clazz) {
            logger = LoggerFactory.getLogger(clazz);
        }

        public void info(String format, /*@Nullable*/ Object... args) {
            synchronized (warningRateLimiter) {
                if (warningRateLimiter.tryAcquire()) {
                    logger.warn(format, args);
                }
            }
        }
    }

    private interface Command {
        boolean run(Tools tools, List<String> args) throws Exception;
    }
}
