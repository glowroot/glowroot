/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.central.repo.CentralRepoModule;
import org.glowroot.central.repo.ConfigRepositoryImpl.AgentConfigListener;
import org.glowroot.central.repo.RepoAdminImpl;
import org.glowroot.central.repo.SchemaUpgrade;
import org.glowroot.central.repo.Tools;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.HttpClient;
import org.glowroot.common2.repo.util.MailService;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.ui.CommonHandler;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.SessionMapFactory;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.util.concurrent.TimeUnit.MINUTES;

public class CentralModule {

    // need to wait to init logger until after establishing centralDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    private final ClusterManager clusterManager;
    private final Cluster cluster;
    private final Session session;
    private final AlertingService alertingService;
    private final CentralAlertingService centralAlertingService;
    private final GrpcServer grpcServer;
    private final UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService;
    private final RollupService rollupService;
    private final SyntheticMonitorService syntheticMonitorService;
    private final UiModule uiModule;

    public static CentralModule create() throws Exception {
        CodeSource codeSource = CentralModule.class.getProtectionDomain().getCodeSource();
        return create(getCentralDir(codeSource));
    }

    @VisibleForTesting
    public static CentralModule create(File centralDir) throws Exception {
        return new CentralModule(centralDir, false);
    }

    static CentralModule createForServletContainer(File centralDir) throws Exception {
        return new CentralModule(centralDir, true);
    }

    private CentralModule(File centralDir, boolean servlet) throws Exception {
        ClusterManager clusterManager = null;
        Cluster cluster = null;
        Session session = null;
        AlertingService alertingService = null;
        CentralAlertingService centralAlertingService = null;
        GrpcServer grpcServer = null;
        UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService = null;
        RollupService rollupService = null;
        SyntheticMonitorService syntheticMonitorService = null;
        UiModule uiModule = null;
        try {
            // init logger as early as possible
            File logDir = getLogDir(centralDir);
            initLogging(centralDir, logDir);
            Clock clock = Clock.systemClock();
            Ticker ticker = Ticker.systemTicker();
            String version = Version.getVersion(CentralModule.class);
            startupLogger.info("Glowroot version: {}", version);
            startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());
            if (servlet) {
                String extra = "";
                if (Strings.isNullOrEmpty(System.getProperty("glowroot.central.dir"))) {
                    extra = ", this can be changed by adding the JVM arg -Dglowroot.central.dir=..."
                            + " to your servlet container startup";
                }
                startupLogger.info(
                        "Glowroot home: {} (location for glowroot-central.properties file{})",
                        centralDir.getAbsolutePath(), extra);
            }

            CentralConfiguration centralConfig = getCentralConfiguration(centralDir);
            clusterManager = ClusterManager.create(centralDir, centralConfig.jgroupsProperties());
            session = connect(centralConfig);
            cluster = session.getCluster();

            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, clock, servlet);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating glowroot central schema ...");
            } else {
                schemaUpgrade.upgrade();
            }
            if (schemaUpgrade.reloadCentralConfiguration()) {
                centralConfig = getCentralConfiguration(centralDir);
            }
            CentralRepoModule repos = new CentralRepoModule(clusterManager, session,
                    centralConfig.cassandraSymmetricEncryptionKey(), clock);

            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("glowroot central schema created");
            } else {
                schemaUpgrade.updateToMoreRecentCassandraOptions(
                        repos.getConfigRepository().getCentralStorageConfig());
            }

            RollupLevelService rollupLevelService =
                    new RollupLevelService(repos.getConfigRepository(), clock);
            HttpClient httpClient = new HttpClient(repos.getConfigRepository());
            alertingService = new AlertingService(repos.getConfigRepository(),
                    repos.getIncidentDao(), repos.getAggregateDao(), repos.getGaugeValueDao(),
                    rollupLevelService, new MailService(), httpClient, clock);
            HeartbeatAlertingService heartbeatAlertingService = new HeartbeatAlertingService(
                    repos.getHeartbeatDao(), repos.getIncidentDao(), alertingService,
                    repos.getConfigRepository());
            centralAlertingService = new CentralAlertingService(repos.getConfigRepository(),
                    alertingService, heartbeatAlertingService);

            grpcServer = new GrpcServer(centralConfig.grpcBindAddress(),
                    centralConfig.grpcHttpPort(), centralConfig.grpcHttpsPort(), centralDir,
                    repos.getAgentConfigDao(), repos.getAgentDao(), repos.getEnvironmentDao(),
                    repos.getHeartbeatDao(), repos.getAggregateDao(), repos.getGaugeValueDao(),
                    repos.getTraceDao(), repos.getV09AgentRollupDao(), centralAlertingService,
                    clusterManager, clock, version);
            DownstreamServiceImpl downstreamService = grpcServer.getDownstreamService();
            updateAgentConfigIfNeededService = new UpdateAgentConfigIfNeededService(
                    repos.getAgentDao(), repos.getAgentConfigDao(), downstreamService, clock);
            UpdateAgentConfigIfNeededService updateAgentConfigIfNeededServiceEffectivelyFinal =
                    updateAgentConfigIfNeededService;
            repos.getConfigRepository().addAgentConfigListener(new AgentConfigListener() {
                @Override
                public void onChange(String agentId) throws Exception {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(updateAgentConfigIfNeededServiceEffectivelyFinal)
                            .updateAgentConfigIfNeededAndConnected(agentId);
                }
            });
            rollupService = new RollupService(repos.getAgentDao(), repos.getAggregateDao(),
                    repos.getGaugeValueDao(), repos.getSyntheticResultDao(), centralAlertingService,
                    clock);
            syntheticMonitorService = new SyntheticMonitorService(repos.getAgentDao(),
                    repos.getConfigRepository(), repos.getIncidentDao(), alertingService,
                    repos.getSyntheticResultDao(), ticker, clock, version);

            ClusterManager clusterManagerEffectivelyFinal = clusterManager;
            uiModule = new CreateUiModuleBuilder()
                    .central(true)
                    .servlet(servlet)
                    .offlineViewer(false)
                    .bindAddress(centralConfig.uiBindAddress())
                    .port(centralConfig.uiPort())
                    .https(centralConfig.uiHttps())
                    .contextPath(centralConfig.uiContextPath())
                    .confDir(centralDir)
                    .logDir(logDir)
                    .logFileNamePattern(Pattern.compile("glowroot-central.*\\.log"))
                    .clock(clock)
                    .liveJvmService(new LiveJvmServiceImpl(downstreamService))
                    .configRepository(repos.getConfigRepository())
                    .agentRollupRepository(repos.getAgentDao())
                    .environmentRepository(repos.getEnvironmentDao())
                    .transactionTypeRepository(repos.getTransactionTypeDao())
                    .traceAttributeNameRepository(repos.getTraceAttributeNameDao())
                    .traceRepository(repos.getTraceDao())
                    .aggregateRepository(repos.getAggregateDao())
                    .gaugeValueRepository(repos.getGaugeValueDao())
                    .syntheticResultRepository(repos.getSyntheticResultDao())
                    .incidentRepository(repos.getIncidentDao())
                    .repoAdmin(new RepoAdminImpl(session, repos.getConfigRepository(),
                            session.getCassandraWriteMetrics()))
                    .rollupLevelService(rollupLevelService)
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
                uiModule.close();
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
                grpcServer.close();
            }
            if (centralAlertingService != null) {
                centralAlertingService.close();
            }
            if (alertingService != null) {
                alertingService.close();
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

    public void shutdown() {
        if (startupLogger != null) {
            startupLogger.info("shutting down ...");
        }
        try {
            // close down external inputs first (ui and grpc)
            uiModule.close();
            syntheticMonitorService.close();
            rollupService.close();
            // updateAgentConfigIfNeededService depends on grpc downstream, so must be shutdown
            // before grpc
            updateAgentConfigIfNeededService.close();
            grpcServer.close();
            centralAlertingService.close();
            alertingService.close();
            session.close();
            cluster.close();
            clusterManager.close();
            if (startupLogger != null) {
                startupLogger.info("shutdown complete");
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
        } catch (Throwable t) {
            if (startupLogger == null) {
                t.printStackTrace();
            } else {
                startupLogger.error("error during shutdown: {}", t.getMessage(), t);
            }
        }
    }

    private static File getCentralDir(@Nullable CodeSource codeSource) throws URISyntaxException {
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

    static void createSchema() throws Exception {
        File centralDir = new File(".");
        File logDir = getLogDir(centralDir);
        initLogging(centralDir, logDir);
        String version = Version.getVersion(CentralModule.class);
        startupLogger.info("running create-schema command");
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());

        CentralConfiguration centralConfig = getCentralConfiguration(centralDir);
        Session session = null;
        Cluster cluster = null;
        try {
            session = connect(centralConfig);
            cluster = session.getCluster();
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, Clock.systemClock(), false);
            if (schemaUpgrade.getInitialSchemaVersion() != null) {
                startupLogger.error("glowroot central schema already exists, exiting");
                return;
            }
            startupLogger.info("creating glowroot central schema ...");
            new CentralRepoModule(ClusterManager.create(), session,
                    centralConfig.cassandraSymmetricEncryptionKey(), Clock.systemClock());
            schemaUpgrade.updateSchemaVersionToCurent();
        } finally {
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
        File centralDir = new File(".");
        File logDir = getLogDir(centralDir);
        initLogging(centralDir, logDir);
        Command command;
        if (commandName.equals("setup-admin-user")) {
            if (args.size() != 2) {
                startupLogger.error(
                        "setup-admin-user requires two args (username and password), exiting");
                return;
            }
            command = Tools::setupAdminUser;
        } else if (commandName.equals("truncate-all-data")) {
            if (!args.isEmpty()) {
                startupLogger.error("truncate-all-data does not accept any args, exiting");
                return;
            }
            command = Tools::truncateAllData;
        } else if (commandName.equals("execute-range-deletes")) {
            if (args.size() != 2) {
                startupLogger.error("execute-range-deletes requires two args (partial table name"
                        + " and rollup level), exiting");
                return;
            }
            String partialTableName = args.get(0);
            if (!partialTableName.equals("query") && !partialTableName.equals("service_call")
                    && !partialTableName.equals("profile")) {
                startupLogger.error("partial table name must be one of \"query\", \"service_call\""
                        + " or \"profile\", exiting");
                return;
            }
            command = Tools::executeDeletes;
        } else {
            startupLogger.error("unexpected command '{}', exiting", commandName);
            return;
        }

        String version = Version.getVersion(CentralModule.class);
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());

        CentralConfiguration centralConfig = getCentralConfiguration(centralDir);
        Session session = null;
        Cluster cluster = null;
        boolean success;
        try {
            session = connect(centralConfig);
            cluster = session.getCluster();
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, Clock.systemClock(), false);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating glowroot central schema ...");
            } else if (initialSchemaVersion != schemaUpgrade.getCurrentSchemaVersion()) {
                startupLogger.warn("running a version of glowroot central that does not match the"
                        + " glowroot central schema version (expecting glowroot central schema"
                        + " version {} but found version {}), exiting",
                        schemaUpgrade.getCurrentSchemaVersion(), initialSchemaVersion);
                return;
            }
            CentralRepoModule repos = new CentralRepoModule(ClusterManager.create(), session,
                    centralConfig.cassandraSymmetricEncryptionKey(), Clock.systemClock());
            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("glowroot central schema created");
            }
            startupLogger.info("running {}", commandName);
            success = command.run(new Tools(session, repos), args);
        } finally {
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

    private static CentralConfiguration getCentralConfiguration(File centralDir)
            throws IOException {
        Map<String, String> properties = getPropertiesFromConfigFile(centralDir);
        properties = overlayAnySystemProperties(properties);
        ImmutableCentralConfiguration.Builder builder = ImmutableCentralConfiguration.builder();
        String cassandraContactPoints = properties.get("glowroot.cassandra.contactPoints");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraUsername = properties.get("glowroot.cassandra.username");
        if (!Strings.isNullOrEmpty(cassandraUsername)) {
            builder.cassandraUsername(cassandraUsername);
        }
        String cassandraPassword = properties.get("glowroot.cassandra.password");
        if (!Strings.isNullOrEmpty(cassandraPassword)) {
            builder.cassandraPassword(cassandraPassword);
        }
        String cassandraKeyspace = properties.get("glowroot.cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
        }
        String cassandraConsistencyLevel = properties.get("glowroot.cassandra.consistencyLevel");
        if (!Strings.isNullOrEmpty(cassandraConsistencyLevel)) {
            ConsistencyLevel consistencyLevel = ConsistencyLevel.valueOf(cassandraConsistencyLevel);
            builder.cassandraConsistencyLevel(consistencyLevel);
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
        String cassandraPoolMaxRequestsPerConnection =
                properties.get("glowroot.cassandra.pool.maxRequestsPerConnection");
        if (!Strings.isNullOrEmpty(cassandraPoolMaxRequestsPerConnection)) {
            builder.cassandraPoolMaxRequestsPerConnection(
                    Integer.parseInt(cassandraPoolMaxRequestsPerConnection));
        }
        String cassandraPoolMaxQueueSize = properties.get("glowroot.cassandra.pool.maxQueueSize");
        if (!Strings.isNullOrEmpty(cassandraPoolMaxQueueSize)) {
            builder.cassandraPoolMaxQueueSize(Integer.parseInt(cassandraPoolMaxQueueSize));
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

    private static Map<String, String> getPropertiesFromConfigFile(File centralDir)
            throws IOException {
        File propFile = new File(centralDir, "glowroot-central.properties");
        if (!propFile.exists()) {
            // upgrade from 0.9.5 to 0.9.6
            File oldPropFile = new File(centralDir, "glowroot-server.properties");
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
                && !new File(centralDir, jgroupsConfigurationFile).exists()) {
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
        if (!upgradePropertyNames.isEmpty()) {
            PropertiesFiles.upgradeIfNeeded(propFile, upgradePropertyNames);
            props = PropertiesFiles.load(propFile);
        }
        // upgrade from 0.9.15 to 0.9.16
        File secretFile = new File(centralDir, "secret");
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
                    session = new Session(
                            createCluster(centralConfig, defaultTimestampGenerator).connect(),
                            keyspace);
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
                        keyspace, replicationFactor, centralConfig.cassandraConsistencyLevel());
                return session;
            } catch (NoHostAvailableException e) {
                startupLogger.debug(e.getMessage(), e);
                lastException = e;
                if (session == null) {
                    waitingForCassandraLogger.info("waiting for Cassandra ({}) ...",
                            Joiner.on(",").join(centralConfig.cassandraContactPoint()));
                } else {
                    waitingForCassandraReplicasLogger.info("waiting for enough Cassandra replicas"
                            + " to run queries at consistency level {} ({}) ...",
                            centralConfig.cassandraConsistencyLevel(),
                            Joiner.on(",").join(centralConfig.cassandraContactPoint()));
                }
                Thread.sleep(1000);
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
                // aggressive reconnect policy seems ok since not many clients
                .withReconnectionPolicy(new ConstantReconnectionPolicy(1000))
                // let driver know that only idempotent queries are used so it will retry on timeout
                .withQueryOptions(new QueryOptions()
                        .setDefaultIdempotence(true)
                        .setConsistencyLevel(centralConfig.cassandraConsistencyLevel()))
                .withPoolingOptions(new PoolingOptions()
                        .setMaxRequestsPerConnection(HostDistance.LOCAL,
                                centralConfig.cassandraPoolMaxRequestsPerConnection())
                        .setMaxRequestsPerConnection(HostDistance.REMOTE,
                                centralConfig.cassandraPoolMaxRequestsPerConnection())
                        .setMaxQueueSize(centralConfig.cassandraPoolMaxQueueSize())
                        .setPoolTimeoutMillis(centralConfig.cassandraPoolTimeoutMillis()))
                .withTimestampGenerator(defaultTimestampGenerator);
        String cassandraUsername = centralConfig.cassandraUsername();
        if (!cassandraUsername.isEmpty()) {
            // empty password is strange but valid
            builder.withCredentials(cassandraUsername, centralConfig.cassandraPassword());
        }
        return builder.build();
    }

    private static String verifyCassandraVersion(Session session) throws Exception {
        ResultSet results =
                session.execute("select release_version from system.local where key = 'local'");
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

    private static File getLogDir(File centralDir) throws IOException {
        String explicitLogDirPath = System.getProperty("glowroot.log.dir");
        if (Strings.isNullOrEmpty(explicitLogDirPath)) {
            return new File(centralDir, "logs");
        }
        File explicitLogDir = new File(explicitLogDirPath);
        explicitLogDir.mkdirs();
        if (!explicitLogDir.isDirectory()) {
            throw new IOException(
                    "Could not create log directory: " + explicitLogDir.getAbsolutePath());
        }
        return explicitLogDir;
    }

    @Value.Immutable
    abstract static class CentralConfiguration {

        @Value.Default
        List<String> cassandraContactPoint() {
            return ImmutableList.of("127.0.0.1");
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
        String cassandraKeyspace() {
            return "glowroot";
        }

        @Value.Default
        ConsistencyLevel cassandraConsistencyLevel() {
            return ConsistencyLevel.QUORUM;
        }

        @Value.Default
        String cassandraSymmetricEncryptionKey() {
            return "";
        }

        @Value.Default
        int cassandraPoolMaxRequestsPerConnection() {
            return 1024;
        }

        @Value.Default
        int cassandraPoolMaxQueueSize() {
            // central runs lots of parallel async queries and is very spiky since all aggregates
            // come in right after each minute marker
            return 10000;
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
