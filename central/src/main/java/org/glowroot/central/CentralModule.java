/*
 * Copyright 2015-2017 the original author or authors.
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
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.servlet.ServletConfig;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
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
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.CentralConfigDao;
import org.glowroot.central.repo.ConfigDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.ConfigRepositoryImpl.AgentConfigListener;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.FullQueryTextDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.RoleDao;
import org.glowroot.central.repo.SchemaUpgrade;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.repo.TraceAttributeNameDao;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.TransactionTypeDao;
import org.glowroot.central.repo.TriggeredAlertDao;
import org.glowroot.central.repo.UserDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;
import org.glowroot.ui.CommonHandler;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.SessionMapFactory;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

class CentralModule {

    // need to wait to init logger until after establishing centralDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    private final ClusterManager clusterManager;
    private final Cluster cluster;
    private final Session session;
    private final RollupService rollupService;
    private final SyntheticMonitorService syntheticMonitorService;
    private final GrpcServer grpcServer;
    private final UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService;
    private final UiModule uiModule;

    CentralModule() throws Exception {
        this(null);
    }

    CentralModule(@Nullable ServletConfig config) throws Exception {
        ClusterManager clusterManager = null;
        Cluster cluster = null;
        Session session = null;
        RollupService rollupService = null;
        SyntheticMonitorService syntheticMonitorService = null;
        GrpcServer grpcServer = null;
        UpdateAgentConfigIfNeededService updateAgentConfigIfNeededService = null;
        UiModule uiModule = null;
        try {
            File centralDir = config == null ? new File(".") : getCentralDir();
            // init logger as early as possible
            initLogging(centralDir);
            if (config != null) {
                File propFile = new File(centralDir, "glowroot-central.properties");
                if (!propFile.exists()) {
                    try (FileOutputStream out = new FileOutputStream(propFile)) {
                        ByteStreams.copy(config.getServletContext()
                                .getResourceAsStream("/META-INF/glowroot-central.properties"), out);
                    }
                }
            }
            // install jul-to-slf4j bridge for protobuf which logs to jul
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();

            Clock clock = Clock.systemClock();
            Ticker ticker = Ticker.systemTicker();
            String version = Version.getVersion(Bootstrap.class);
            startupLogger.info("Glowroot version: {}", version);
            startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());
            if (config != null) {
                String extra = "";
                if (Strings.isNullOrEmpty(System.getProperty("glowroot.central.dir"))) {
                    extra = ", this can be changed by adding the JVM arg -Dglowroot.central.dir=..."
                            + " to your servlet container startup";
                }
                startupLogger.info("Glowroot home: {} (location for glowroot.properties file{})",
                        centralDir.getAbsolutePath(), extra);
            }

            CentralConfiguration centralConfig = getCentralConfiguration(centralDir);
            clusterManager = ClusterManager.create(centralDir, centralConfig.jgroupsProperties());
            session = connect(centralConfig);
            cluster = session.getCluster();
            Sessions.createKeyspaceIfNotExists(session, centralConfig.cassandraKeyspace());
            session.execute("use " + centralConfig.cassandraKeyspace());

            KeyspaceMetadata keyspace =
                    cluster.getMetadata().getKeyspace(centralConfig.cassandraKeyspace());
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, keyspace, config != null);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating cassandra schema...");
            } else {
                schemaUpgrade.upgrade();
            }
            if (schemaUpgrade.reloadCentralConfiguration()) {
                centralConfig = getCentralConfiguration(centralDir);
            }
            CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
            AgentDao agentDao = new AgentDao(session, clusterManager);
            ConfigDao configDao = new ConfigDao(session, clusterManager);
            UserDao userDao = new UserDao(session, keyspace, clusterManager);
            RoleDao roleDao = new RoleDao(session, keyspace, clusterManager);
            ConfigRepositoryImpl configRepository =
                    new ConfigRepositoryImpl(agentDao, configDao, centralConfigDao, userDao,
                            roleDao, centralConfig.cassandraSymmetricEncryptionKey());
            if (initialSchemaVersion != null) {
                schemaUpgrade.updateToMoreRecentCassandraOptions(
                        configRepository.getCentralStorageConfig());
            }
            TransactionTypeDao transactionTypeDao =
                    new TransactionTypeDao(session, configRepository, clusterManager);
            FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(session, configRepository);
            AggregateDao aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, configRepository, clock);
            TraceAttributeNameDao traceAttributeNameDao =
                    new TraceAttributeNameDao(session, configRepository, clusterManager);
            TraceDao traceDao = new TraceDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, traceAttributeNameDao, configRepository, clock);
            GaugeValueDao gaugeValueDao =
                    new GaugeValueDao(session, agentDao, configRepository, clusterManager, clock);
            SyntheticResultDao syntheticResultDao =
                    new SyntheticResultDao(session, configRepository, clock);
            EnvironmentDao environmentDao = new EnvironmentDao(session);
            HeartbeatDao heartbeatDao = new HeartbeatDao(session, agentDao, clock);
            TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(session);
            RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);
            AlertingService alertingService = new AlertingService(configRepository,
                    triggeredAlertDao, aggregateDao, gaugeValueDao, rollupLevelService,
                    new MailService());

            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("cassandra schema created");
            }

            grpcServer = new GrpcServer(centralConfig.grpcBindAddress(), centralConfig.grpcPort(),
                    agentDao, configDao, aggregateDao, gaugeValueDao, environmentDao, heartbeatDao,
                    traceDao, configRepository, alertingService, clusterManager, clock, version);
            DownstreamServiceImpl downstreamService = grpcServer.getDownstreamService();
            updateAgentConfigIfNeededService = new UpdateAgentConfigIfNeededService(agentDao,
                    configDao, downstreamService, clock);
            UpdateAgentConfigIfNeededService updateAgentConfigIfNeededServiceEffectivelyFinal =
                    updateAgentConfigIfNeededService;
            configRepository.addAgentConfigListener(new AgentConfigListener() {
                @Override
                public void onChange(String agentId) throws Exception {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(updateAgentConfigIfNeededServiceEffectivelyFinal)
                            .updateAgentConfigIfNeededAndConnected(agentId);
                }
            });
            rollupService = new RollupService(agentDao, aggregateDao, gaugeValueDao,
                    syntheticResultDao, heartbeatDao, configRepository, alertingService, clock);
            syntheticMonitorService = new SyntheticMonitorService(agentDao, configRepository,
                    triggeredAlertDao, alertingService, syntheticResultDao, ticker, clock);

            ClusterManager clusterManagerEffectivelyFinal = clusterManager;
            uiModule = new CreateUiModuleBuilder()
                    .central(true)
                    .servlet(config != null)
                    .offline(false)
                    .bindAddress(centralConfig.uiBindAddress())
                    .port(centralConfig.uiPort())
                    .https(centralConfig.uiHttps())
                    .contextPath(centralConfig.uiContextPath())
                    .certificateDir(centralDir)
                    .logDir(centralDir)
                    .clock(clock)
                    .liveJvmService(new LiveJvmServiceImpl(downstreamService))
                    .configRepository(configRepository)
                    .agentRepository(agentDao)
                    .environmentRepository(environmentDao)
                    .transactionTypeRepository(transactionTypeDao)
                    .traceAttributeNameRepository(traceAttributeNameDao)
                    .traceRepository(traceDao)
                    .aggregateRepository(aggregateDao)
                    .gaugeValueRepository(gaugeValueDao)
                    .syntheticResultRepository(syntheticResultDao)
                    .triggeredAlertRepository(triggeredAlertDao)
                    .repoAdmin(new NopRepoAdmin())
                    .rollupLevelService(rollupLevelService)
                    .liveTraceRepository(new LiveTraceRepositoryImpl(downstreamService, agentDao))
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceImpl(downstreamService))
                    .sessionMapFactory(new SessionMapFactory() {
                        @Override
                        public <V extends /*@NonNull*/ Serializable> ConcurrentMap<String, V> create() {
                            return clusterManagerEffectivelyFinal.createReplicatedMap("sessionMap");
                        }
                    })
                    .numWorkerThreads(50)
                    .version(version)
                    .build();
        } catch (Throwable t) {
            if (startupLogger == null) {
                t.printStackTrace();
            } else {
                startupLogger.error(t.getMessage(), t);
            }
            // try to shut down cleanly, otherwise apache commons daemon (via Bootstrap) doesn't
            // know service failed to start up
            if (uiModule != null) {
                uiModule.close(false);
            }
            if (updateAgentConfigIfNeededService != null) {
                updateAgentConfigIfNeededService.close();
            }
            if (grpcServer != null) {
                grpcServer.close();
            }
            if (syntheticMonitorService != null) {
                syntheticMonitorService.close();
            }
            if (rollupService != null) {
                rollupService.close();
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
        this.rollupService = rollupService;
        this.syntheticMonitorService = syntheticMonitorService;
        this.grpcServer = grpcServer;
        this.updateAgentConfigIfNeededService = updateAgentConfigIfNeededService;
        this.uiModule = uiModule;
    }

    CommonHandler getCommonHandler() {
        return uiModule.getCommonHandler();
    }

    void shutdown() {
        if (startupLogger != null) {
            startupLogger.info("shutting down...");
        }
        try {
            // close down external inputs first (ui and grpc)
            uiModule.close(false);
            // updateAgentConfigIfNeededService depends on grpc downstream, so must be shutdown
            // before grpc
            updateAgentConfigIfNeededService.close();
            grpcServer.close();
            syntheticMonitorService.close();
            rollupService.close();
            session.close();
            cluster.close();
            clusterManager.close();
            if (startupLogger != null) {
                startupLogger.info("shutdown complete");
            }
        } catch (Throwable t) {
            if (startupLogger == null) {
                t.printStackTrace();
            } else {
                startupLogger.error("error during shutdown: {}", t.getMessage(), t);
            }
        }
    }

    private static CentralConfiguration getCentralConfiguration(File centralDir)
            throws IOException {
        ImmutableCentralConfiguration.Builder builder = ImmutableCentralConfiguration.builder();
        File propFile = new File(centralDir, "glowroot-central.properties");
        if (!propFile.exists()) {
            // upgrade from 0.9.5 to 0.9.6
            File oldPropFile = new File(centralDir, "glowroot-server.properties");
            if (!oldPropFile.exists()) {
                return builder.build();
            }
            java.nio.file.Files.copy(oldPropFile.toPath(), propFile.toPath());
        }
        Properties props = PropertiesFiles.load(propFile);

        Map<String, String> upgradePropertyNames = Maps.newHashMap();
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
                byte[] bytes = Files.toByteArray(secretFile);
                String newValue = BaseEncoding.base16().lowerCase().encode(bytes);
                if (existingValue == null) {
                    FileWriter out = new FileWriter(propFile, true);
                    out.write("\ncassandra.symmetricEncryptionKey=");
                    out.write(newValue);
                    out.write("\n");
                    out.close();
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
        String cassandraContactPoints = props.getProperty("cassandra.contactPoints");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraKeyspace = props.getProperty("cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
        }
        String cassandraConsistencyLevel = props.getProperty("cassandra.consistencyLevel");
        if (!Strings.isNullOrEmpty(cassandraConsistencyLevel)) {
            ConsistencyLevel consistencyLevel = ConsistencyLevel.valueOf(cassandraConsistencyLevel);
            builder.cassandraConsistencyLevel(consistencyLevel);
        }
        String cassandraSymmetricEncryptionKey =
                props.getProperty("cassandra.symmetricEncryptionKey");
        if (!Strings.isNullOrEmpty(cassandraSymmetricEncryptionKey)) {
            if (!cassandraSymmetricEncryptionKey.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalStateException("Invalid cassandra.symmetricEncryptionKey value,"
                        + " it must be a 32 character hex string");
            }
            builder.cassandraSymmetricEncryptionKey(cassandraSymmetricEncryptionKey);
        }
        String cassandraUsername = props.getProperty("cassandra.username");
        if (!Strings.isNullOrEmpty(cassandraUsername)) {
            builder.cassandraUsername(cassandraUsername);
        }
        String cassandraPassword = props.getProperty("cassandra.password");
        if (!Strings.isNullOrEmpty(cassandraPassword)) {
            builder.cassandraPassword(cassandraPassword);
        }
        String grpcBindAddress = props.getProperty("grpc.bindAddress");
        if (!Strings.isNullOrEmpty(grpcBindAddress)) {
            builder.grpcBindAddress(grpcBindAddress);
        }
        String grpcPortText = props.getProperty("grpc.port");
        if (!Strings.isNullOrEmpty(grpcPortText)) {
            builder.grpcPort(Integer.parseInt(grpcPortText));
        }
        String uiBindAddress = props.getProperty("ui.bindAddress");
        if (!Strings.isNullOrEmpty(uiBindAddress)) {
            builder.uiBindAddress(uiBindAddress);
        }
        String uiPortText = props.getProperty("ui.port");
        if (!Strings.isNullOrEmpty(uiPortText)) {
            builder.uiPort(Integer.parseInt(uiPortText));
        }
        String uiHttpsText = props.getProperty("ui.https");
        if (!Strings.isNullOrEmpty(uiHttpsText)) {
            builder.uiHttps(Boolean.parseBoolean(uiHttpsText));
        }
        String uiContextPath = props.getProperty("ui.contextPath");
        if (!Strings.isNullOrEmpty(uiContextPath)) {
            builder.uiContextPath(uiContextPath);
        }
        for (String propName : props.stringPropertyNames()) {
            if (propName.startsWith("jgroups.")) {
                String propValue = props.getProperty(propName);
                if (!Strings.isNullOrEmpty(propValue)) {
                    builder.putJgroupsProperties(propName, propValue);
                }
            }
        }
        return builder.build();
    }

    @RequiresNonNull("startupLogger")
    private static Session connect(CentralConfiguration centralConfig) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean waitingForCassandraLogged = false;
        NoHostAvailableException lastException = null;
        while (stopwatch.elapsed(MINUTES) < 10) {
            try {
                Cluster.Builder builder = Cluster.builder()
                        .addContactPoints(
                                centralConfig.cassandraContactPoint().toArray(new String[0]))
                        // aggressive reconnect policy seems ok since not many clients
                        .withReconnectionPolicy(new ConstantReconnectionPolicy(1000))
                        // let driver know that only idempotent queries are used so it will retry on
                        // timeout
                        .withQueryOptions(new QueryOptions()
                                .setDefaultIdempotence(true)
                                .setConsistencyLevel(centralConfig.cassandraConsistencyLevel()))
                        // central runs lots of parallel async queries and is very spiky since all
                        // aggregates come in right after each minute marker
                        .withPoolingOptions(new PoolingOptions().setMaxQueueSize(4096));
                String cassandraUsername = centralConfig.cassandraUsername();
                if (!cassandraUsername.isEmpty()) {
                    // empty password is strange but valid
                    builder.withCredentials(cassandraUsername, centralConfig.cassandraPassword());
                }
                Session session = builder.build().connect();
                ResultSet results = session
                        .execute("select release_version from system.local where key = 'local'");
                Row row = checkNotNull(results.one());
                String cassandraVersion = checkNotNull(row.getString(0));
                if (cassandraVersion.startsWith("2.0") || cassandraVersion.startsWith("1.")
                        || cassandraVersion.startsWith("0.")) {
                    throw new IllegalStateException(
                            "Glowroot central requires Cassandra 2.1+, but found: "
                                    + cassandraVersion);
                }
                startupLogger.info("Cassandra version: {}", cassandraVersion);
                return session;
            } catch (NoHostAvailableException e) {
                startupLogger.debug(e.getMessage(), e);
                lastException = e;
                if (!waitingForCassandraLogged) {
                    startupLogger.info("waiting for cassandra ({}) ...",
                            Joiner.on(",").join(centralConfig.cassandraContactPoint()));
                }
                waitingForCassandraLogged = true;
                Thread.sleep(1000);
            }
        }
        checkNotNull(lastException);
        throw lastException;
    }

    private static File getCentralDir() throws IOException {
        String centralDirPath = System.getProperty("glowroot.central.dir");
        if (Strings.isNullOrEmpty(centralDirPath)) {
            return getDefaultCentralDir();
        }
        File centralDir = new File(centralDirPath);
        centralDir.mkdirs();
        if (!centralDir.isDirectory()) {
            // not using logger since the home dir is needed to set up the logger
            return getDefaultCentralDir();
        }
        return centralDir;
    }

    private static File getDefaultCentralDir() throws IOException {
        File centralDir = new File("glowroot-central");
        if (!centralDir.exists()) {
            // upgrade from 0.9.11 to 0.9.12 if needed
            File oldCentralDir = new File("glowroot");
            if (oldCentralDir.exists()) {
                oldCentralDir.renameTo(centralDir);
            }
        }
        centralDir.mkdirs();
        if (!centralDir.isDirectory()) {
            throw new IOException("Could not create directory: " + centralDir.getAbsolutePath());
        }
        return centralDir;
    }

    // TODO report checker framework issue that occurs without this suppression
    @EnsuresNonNull("startupLogger")
    @SuppressWarnings("contracts.postcondition.not.satisfied")
    private static void initLogging(File centralDir) throws IOException {
        File logbackXmlOverride = new File(centralDir, "logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("logback.configurationFile", logbackXmlOverride.getAbsolutePath());
        }
        String explicitLogDirPath = System.getProperty("glowroot.log.dir");
        try {
            if (Strings.isNullOrEmpty(explicitLogDirPath)) {
                System.setProperty("glowroot.log.dir", centralDir.getPath());
            } else {
                File explicitLogDir = new File(explicitLogDirPath);
                explicitLogDir.mkdirs();
                if (!explicitLogDir.isDirectory()) {
                    throw new IOException(
                            "Could not create log directory: " + explicitLogDir.getAbsolutePath());
                }
            }
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            System.clearProperty("logback.configurationFile");
            if (explicitLogDirPath == null) {
                System.clearProperty("glowroot.log.dir");
            } else if (explicitLogDirPath.isEmpty()) {
                System.setProperty("glowroot.log.dir", "");
            }
        }
    }

    @Value.Immutable
    static abstract class CentralConfiguration {

        @Value.Default
        @SuppressWarnings("immutables")
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
            return ConsistencyLevel.LOCAL_ONE;
        }

        @Value.Default
        String cassandraSymmetricEncryptionKey() {
            return "";
        }

        @Value.Default
        String grpcBindAddress() {
            return "0.0.0.0";
        }

        @Value.Default
        int grpcPort() {
            return 8181;
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

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void deleteAllData() throws Exception {}
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNeeded() throws Exception {}
    }
}
