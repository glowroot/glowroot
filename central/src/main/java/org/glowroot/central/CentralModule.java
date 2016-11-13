/*
 * Copyright 2015-2016 the original author or authors.
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.CentralConfigDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.ConfigRepositoryImpl.ConfigListener;
import org.glowroot.central.repo.FullQueryTextDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.RoleDao;
import org.glowroot.central.repo.SchemaUpgrade;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.TransactionTypeDao;
import org.glowroot.central.repo.TriggeredAlertDao;
import org.glowroot.central.repo.UserDao;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.ImmutableWebConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

class CentralModule {

    private static final Logger startupLogger;

    static {
        CodeSource codeSource = CentralModule.class.getProtectionDomain().getCodeSource();
        File glowrootCentralJarFile = null;
        Exception exception = null;
        try {
            glowrootCentralJarFile = getGlowrootCentralJarFile(codeSource);
        } catch (URISyntaxException e) {
            exception = e;
        }
        if (glowrootCentralJarFile != null) {
            File logbackXmlOverride =
                    new File(glowrootCentralJarFile.getParentFile(), "logback.xml");
            if (logbackXmlOverride.exists()) {
                System.setProperty("logback.configurationFile",
                        logbackXmlOverride.getAbsolutePath());
            }
        }
        startupLogger = LoggerFactory.getLogger("org.glowroot");
        if (exception != null) {
            startupLogger.error(exception.getMessage(), exception);
        }
    }

    private final Cluster cluster;
    private final Session session;
    private final RollupService rollupService;
    private final GrpcServer server;
    private final UiModule uiModule;

    CentralModule() throws Exception {
        Cluster cluster = null;
        Session session = null;
        RollupService rollupService = null;
        GrpcServer server = null;
        UiModule uiModule = null;
        try {
            // install jul-to-slf4j bridge for protobuf which logs to jul
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();

            Clock clock = Clock.systemClock();
            String version = Version.getVersion(Bootstrap.class);
            startupLogger.info("Glowroot version: {}", version);

            CentralConfiguration centralConfig = getCentralConfiguration();
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
            UserDao userDao = new UserDao(session, keyspace);
            RoleDao roleDao = new RoleDao(session, keyspace);
            ConfigRepositoryImpl configRepository =
                    new ConfigRepositoryImpl(centralConfigDao, agentDao, userDao, roleDao);

            if (initialSchemaVersion != null) {
                schemaUpgrade.updateToMoreRecentCassandraOptions(
                        configRepository.getCentralStorageConfig());
            }

            String uiBindAddressOverride = centralConfig.uiBindAddressOverride();
            Integer uiPortOverride = centralConfig.uiPortOverride();
            if (uiBindAddressOverride != null || uiPortOverride != null) {
                // TODO supplying ui.bindAddress in glowroot-central.properties should make the bind
                // address non-editable in admin UI, and supplying ui.port in
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
            centralConfigDao.setConfigRepository(configRepository);
            agentDao.setConfigRepository(configRepository);

            TransactionTypeDao transactionTypeDao =
                    new TransactionTypeDao(session, configRepository);
            FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(session, configRepository);
            AggregateDao aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, configRepository, clock);
            TraceDao traceDao = new TraceDao(session, agentDao, transactionTypeDao,
                    fullQueryTextDao, configRepository, clock);
            GaugeValueDao gaugeValueDao =
                    new GaugeValueDao(session, agentDao, configRepository, clock);
            TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(session, configRepository);
            RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);
            AlertingService alertingService = new AlertingService(configRepository,
                    triggeredAlertDao, aggregateDao, gaugeValueDao, rollupLevelService,
                    new MailService());

            if (initialSchemaVersion == null) {
                schemaUpgrade.updateSchemaVersionToCurent();
                startupLogger.info("cassandra schema created");
            }

            server = new GrpcServer(centralConfig.grpcBindAddress(), centralConfig.grpcPort(),
                    agentDao, aggregateDao, gaugeValueDao, traceDao, configRepository,
                    alertingService, clock, version);
            DownstreamServiceImpl downstreamService = server.getDownstreamService();
            configRepository.addConfigListener(new ConfigListener() {
                @Override
                public void onChange(String agentId) throws Exception {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(downstreamService).updateAgentConfigIfConnectedAndNeeded(agentId);
                }
            });
            rollupService = new RollupService(agentDao, aggregateDao, gaugeValueDao,
                    configRepository, alertingService, downstreamService, clock);

            uiModule = new CreateUiModuleBuilder()
                    .embedded(false)
                    .offline(false)
                    .clock(clock)
                    .logDir(new File("."))
                    .liveJvmService(new LiveJvmServiceImpl(downstreamService))
                    .configRepository(configRepository)
                    .agentRepository(agentDao)
                    .transactionTypeRepository(transactionTypeDao)
                    .traceRepository(traceDao)
                    .aggregateRepository(aggregateDao)
                    .gaugeValueRepository(gaugeValueDao)
                    .repoAdmin(new NopRepoAdmin())
                    .rollupLevelService(rollupLevelService)
                    .liveTraceRepository(new LiveTraceRepositoryImpl(downstreamService, agentDao))
                    .liveAggregateRepository(new LiveAggregateRepositoryNop())
                    .liveWeavingService(new LiveWeavingServiceImpl(downstreamService))
                    .numWorkerThreads(50)
                    .version(version)
                    .build();
        } catch (Throwable t) {
            startupLogger.error(t.getMessage(), t);
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
            if (session != null) {
                session.close();
            }
            if (cluster != null) {
                cluster.close();
            }
            throw Throwables.propagate(t);
        }
        this.cluster = cluster;
        this.session = session;
        this.rollupService = rollupService;
        this.server = server;
        this.uiModule = uiModule;
    }

    @VisibleForTesting
    static @Nullable File getGlowrootCentralJarFile(@Nullable CodeSource codeSource)
            throws URISyntaxException {
        if (codeSource == null) {
            return null;
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            return codeSourceFile;
        }
        return null;
    }

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

    void close() throws InterruptedException {
        uiModule.close(false);
        server.close();
        rollupService.close();
        session.close();
        cluster.close();
    }

    private static CentralConfiguration getCentralConfiguration() throws IOException {
        ImmutableCentralConfiguration.Builder builder = ImmutableCentralConfiguration.builder();
        File propFile = new File("glowroot-central.properties");
        if (!propFile.exists()) {
            // upgrade from 0.9.5 to 0.9.6
            propFile = new File("glowroot-server.properties");
            if (!propFile.exists()) {
                return builder.build();
            }
            java.nio.file.Files.copy(Paths.get("glowroot-server.properties"),
                    Paths.get("glowroot-central.properties"));
            propFile = new File("glowroot-central.properties");
        }
        // upgrade from 0.9.4 to 0.9.5
        String content = Files.toString(propFile, Charsets.UTF_8);
        if (content.contains("cassandra.contact.points=")) {
            content = content.replace("cassandra.contact.points=", "cassandra.contactPoints=");
            Files.write(content, propFile, Charsets.UTF_8);
        }
        Properties props = new Properties();
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
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

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void deleteAllData() throws Exception {}
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNecessary() throws Exception {}
    }
}
