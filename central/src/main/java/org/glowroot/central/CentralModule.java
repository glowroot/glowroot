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
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.servlet.ServletConfig;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
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
import org.glowroot.central.util.MailService;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.ImmutableWebConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;
import org.glowroot.ui.CommonHandler;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

class CentralModule {

    // need to wait to init logger until after establishing centralDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    private final Cluster cluster;
    private final Session session;
    private final RollupService rollupService;
    private final SyntheticMonitorService pingAndSyntheticAlertService;
    private final GrpcServer server;
    private final UiModule uiModule;

    CentralModule() throws Exception {
        this(null);
    }

    CentralModule(@Nullable ServletConfig config) throws Exception {
        Cluster cluster = null;
        Session session = null;
        RollupService rollupService = null;
        SyntheticMonitorService pingAndSyntheticAlertService = null;
        GrpcServer server = null;
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
            session = connect(centralConfig);
            cluster = session.getCluster();
            Sessions.createKeyspaceIfNotExists(session, centralConfig.cassandraKeyspace());
            session.execute("use " + centralConfig.cassandraKeyspace());

            KeyspaceMetadata keyspace =
                    cluster.getMetadata().getKeyspace(centralConfig.cassandraKeyspace());
            SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, keyspace);
            Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
            if (initialSchemaVersion == null) {
                startupLogger.info("creating cassandra schema...");
            } else {
                schemaUpgrade.upgrade();
            }
            CentralConfigDao centralConfigDao = new CentralConfigDao(session);
            AgentDao agentDao = new AgentDao(session);
            ConfigDao configDao = new ConfigDao(session);
            UserDao userDao = new UserDao(session, keyspace);
            RoleDao roleDao = new RoleDao(session, keyspace);
            ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(agentDao, configDao,
                    centralConfigDao, userDao, roleDao);

            if (initialSchemaVersion != null) {
                schemaUpgrade.updateToMoreRecentCassandraOptions(
                        configRepository.getCentralStorageConfig());
            }

            if (config == null) {
                String uiBindAddressOverride = centralConfig.uiBindAddressOverride();
                Integer uiPortOverride = centralConfig.uiPortOverride();
                if (uiBindAddressOverride != null || uiPortOverride != null) {
                    // TODO supplying ui.bindAddress in glowroot-central.properties should make the
                    // bind address non-editable in admin UI, and supplying ui.port in
                    // glowroot-central.properties should make the port non-editable in admin UI
                    WebConfig webConfig = configRepository.getWebConfig();
                    ImmutableWebConfig updatedWebConfig = ImmutableWebConfig.copyOf(webConfig);
                    if (uiBindAddressOverride != null) {
                        updatedWebConfig = updatedWebConfig.withBindAddress(uiBindAddressOverride);
                    }
                    if (uiPortOverride != null) {
                        updatedWebConfig = updatedWebConfig.withPort(uiPortOverride);
                    }
                    configRepository.updateWebConfig(updatedWebConfig, webConfig.version());
                }
            }

            TransactionTypeDao transactionTypeDao =
                    new TransactionTypeDao(session, configRepository);
            FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(session, configRepository);
            AggregateDao aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, configRepository, clock);
            TraceAttributeNameDao traceAttributeNameDao =
                    new TraceAttributeNameDao(session, configRepository);
            TraceDao traceDao = new TraceDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, traceAttributeNameDao, configRepository, clock);
            GaugeValueDao gaugeValueDao =
                    new GaugeValueDao(session, agentDao, configRepository, clock);
            SyntheticResultDao syntheticResultDao =
                    new SyntheticResultDao(session, configRepository, clock);
            EnvironmentDao environmentDao = new EnvironmentDao(session);
            HeartbeatDao heartbeatDao = new HeartbeatDao(session, agentDao, clock);
            TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(session);
            RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);
            MailService mailService = new MailService();
            AlertingService alertingService = new AlertingService(configRepository,
                    triggeredAlertDao, aggregateDao, gaugeValueDao, rollupLevelService,
                    mailService);

            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("cassandra schema created");
            }

            server = new GrpcServer(centralConfig.grpcBindAddress(), centralConfig.grpcPort(),
                    agentDao, configDao, aggregateDao, gaugeValueDao, environmentDao, heartbeatDao,
                    traceDao, configRepository, alertingService, clock, version);
            DownstreamServiceImpl downstreamService = server.getDownstreamService();
            configRepository.addAgentConfigListener(new AgentConfigListener() {
                @Override
                public void onChange(String agentId) throws Exception {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(downstreamService).updateAgentConfigIfConnectedAndNeeded(agentId);
                }
            });
            rollupService = new RollupService(agentDao, aggregateDao, gaugeValueDao,
                    syntheticResultDao, heartbeatDao, configRepository, alertingService,
                    downstreamService, clock);
            pingAndSyntheticAlertService = new SyntheticMonitorService(agentDao, configRepository,
                    triggeredAlertDao, alertingService, syntheticResultDao, ticker, clock);

            uiModule = new CreateUiModuleBuilder()
                    .central(true)
                    .servlet(config != null)
                    .offline(false)
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
                    .repoAdmin(new RepoAdminImpl(mailService))
                    .rollupLevelService(rollupLevelService)
                    .liveTraceRepository(new LiveTraceRepositoryImpl(downstreamService, agentDao))
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceImpl(downstreamService))
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
            if (server != null) {
                server.close();
            }
            if (rollupService != null) {
                rollupService.close();
            }
            if (pingAndSyntheticAlertService != null) {
                pingAndSyntheticAlertService.close();
            }
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
            throw t;
        }
        this.cluster = cluster;
        this.session = session;
        this.rollupService = rollupService;
        this.pingAndSyntheticAlertService = pingAndSyntheticAlertService;
        this.server = server;
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
            uiModule.close(false);
            server.close();
            rollupService.close();
            pingAndSyntheticAlertService.close();
            session.close();
            cluster.close();
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
        // upgrade from 0.9.4 to 0.9.5
        PropertiesFiles.upgradeIfNeeded(propFile, "cassandra.contact.points=",
                "cassandra.contactPoints=");
        Properties props = PropertiesFiles.load(propFile);
        String cassandraContactPoints = props.getProperty("cassandra.contactPoints");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraKeyspace = props.getProperty("cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
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
            builder.uiBindAddressOverride(uiBindAddress);
        }
        String uiPortText = props.getProperty("ui.port");
        if (!Strings.isNullOrEmpty(uiPortText)) {
            builder.uiPortOverride(Integer.parseInt(uiPortText));
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
                Cluster cluster = Cluster.builder()
                        .addContactPoints(
                                centralConfig.cassandraContactPoint().toArray(new String[0]))
                        // aggressive reconnect policy seems ok since not many clients
                        .withReconnectionPolicy(new ConstantReconnectionPolicy(1000))
                        // let driver know that only idempotent queries are used so it will retry on
                        // timeout
                        .withQueryOptions(new QueryOptions().setDefaultIdempotence(true))
                        // central runs lots of parallel async queries and is very spiky since all
                        // aggregates come in right after each minute marker
                        .withPoolingOptions(new PoolingOptions().setMaxQueueSize(4096))
                        .build();
                return cluster.connect();
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
    private static void initLogging(File centralDir) {
        File logbackXmlOverride = new File(centralDir, "logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("logback.configurationFile", logbackXmlOverride.getAbsolutePath());
        }
        String prior = System.getProperty("glowroot.log.dir");
        try {
            System.setProperty("glowroot.log.dir", centralDir.getPath());
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            System.clearProperty("glowroot.logback.configurationFile");
            if (prior == null) {
                System.clearProperty("glowroot.log.dir");
            } else {
                System.setProperty("glowroot.log.dir", prior);
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
        String cassandraKeyspace() {
            return "glowroot";
        }
        @Value.Default
        String grpcBindAddress() {
            return "0.0.0.0";
        }
        @Value.Default
        int grpcPort() {
            return 8181;
        }
        abstract @Nullable String uiBindAddressOverride();
        abstract @Nullable Integer uiPortOverride();
    }

    private static class RepoAdminImpl implements RepoAdmin {

        private final MailService mailService;

        private RepoAdminImpl(MailService mailService) {
            this.mailService = mailService;
        }

        @Override
        public void deleteAllData() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void defrag() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resizeIfNeeded() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendTestEmail(List<String> emailAddresses, String subject, String messageText,
                SmtpConfig smtpConfig, SecretKey secretKey) throws Exception {
            AlertingService.sendEmail(emailAddresses, subject, messageText, smtpConfig, secretKey,
                    mailService);
        }
    }
}
