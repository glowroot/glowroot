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
package org.glowroot.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.server.storage.AgentDao;
import org.glowroot.server.storage.AggregateDao;
import org.glowroot.server.storage.ConfigRepositoryImpl;
import org.glowroot.server.storage.GaugeValueDao;
import org.glowroot.server.storage.ServerConfigDao;
import org.glowroot.server.storage.TraceDao;
import org.glowroot.server.storage.TransactionTypeDao;
import org.glowroot.server.storage.TriggeredAlertDao;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.util.MailService;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public class ServerModule {

    private static final Logger logger = LoggerFactory.getLogger(ServerModule.class);

    private final Cluster cluster;
    private final Session session;
    private final GrpcServer server;
    private final UiModule uiModule;

    ServerModule() throws Exception {
        Cluster cluster = null;
        Session session = null;
        GrpcServer server = null;
        UiModule uiModule = null;
        try {
            // install jul-to-slf4j bridge for protobuf which logs to jul
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();

            Clock clock = Clock.systemClock();
            String version = Version.getVersion(Bootstrap.class);

            ServerConfiguration serverConfig = getCassandraContactPoints();
            Stopwatch stopwatch = Stopwatch.createStarted();
            boolean waitingForCassandraLogged = false;
            NoHostAvailableException lastException = null;
            while (stopwatch.elapsed(MINUTES) < 10) {
                try {
                    cluster = Cluster.builder()
                            .addContactPoints(
                                    serverConfig.cassandraContactPoint().toArray(new String[0]))
                            .build();
                    session = cluster.connect();
                    break;
                } catch (NoHostAvailableException e) {
                    lastException = e;
                    if (!waitingForCassandraLogged) {
                        logger.info("waiting for cassandra...");
                    }
                    waitingForCassandraLogged = true;
                    Thread.sleep(1000);
                }
            }
            if (cluster == null) {
                checkNotNull(lastException);
                throw lastException;
            }
            checkNotNull(session);
            session.execute("create keyspace if not exists " + serverConfig.cassandraKeyspace()
                    + " with replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
            session.execute("use " + serverConfig.cassandraKeyspace());

            AgentDao agentDao = new AgentDao(session);
            TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session);

            ServerConfigDao serverConfigDao = new ServerConfigDao(session);
            ConfigRepositoryImpl configRepository =
                    new ConfigRepositoryImpl(agentDao, serverConfigDao);

            AggregateRepository aggregateRepository =
                    new AggregateDao(session, agentDao, transactionTypeDao, configRepository);
            TraceRepository traceRepository = new TraceDao(session, agentDao, transactionTypeDao);
            GaugeValueRepository gaugeValueRepository =
                    new GaugeValueDao(session, agentDao, configRepository);
            TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(session);
            RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);
            AlertingService alertingService = new AlertingService(configRepository, agentDao,
                    triggeredAlertDao, aggregateRepository, gaugeValueRepository,
                    rollupLevelService, new MailService());

            server = new GrpcServer(serverConfig.grpcPort(), agentDao, aggregateRepository,
                    gaugeValueRepository, traceRepository, alertingService);
            configRepository.setDownstreamService(server.getDownstreamService());

            uiModule = new CreateUiModuleBuilder()
                    .fat(false)
                    .clock(clock)
                    .logDir(new File("."))
                    .liveJvmService(new LiveJvmServiceImpl(server.getDownstreamService()))
                    .configRepository(configRepository)
                    .agentRepository(agentDao)
                    .transactionTypeRepository(transactionTypeDao)
                    .traceRepository(traceRepository)
                    .aggregateRepository(aggregateRepository)
                    .gaugeValueRepository(gaugeValueRepository)
                    .repoAdmin(new NopRepoAdmin())
                    .rollupLevelService(rollupLevelService)
                    .liveTraceRepository(new LiveTraceRepositoryImpl(server.getDownstreamService()))
                    .liveWeavingService(new LiveWeavingServiceImpl(server.getDownstreamService()))
                    .bindAddress("0.0.0.0")
                    .numWorkerThreads(50)
                    .version(version)
                    .build();
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            // try to shut down cleanly, otherwise apache commons daemon (via Bootstrap) doesn't
            // know service failed to start up
            if (uiModule != null) {
                uiModule.close();
            }
            if (server != null) {
                server.close();
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
        this.server = server;
        this.uiModule = uiModule;
    }

    void close() throws InterruptedException {
        uiModule.close();
        server.close();
        session.close();
        cluster.close();
    }

    private static ServerConfiguration getCassandraContactPoints() throws IOException {
        ImmutableServerConfiguration.Builder builder = ImmutableServerConfiguration.builder();
        File propFile = new File("glowroot-server.properties");
        if (!propFile.exists()) {
            return builder.build();
        }
        Properties props = new Properties();
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        String cassandraContactPoints = props.getProperty("cassandra.contact.points");
        if (!Strings.isNullOrEmpty(cassandraContactPoints)) {
            builder.cassandraContactPoint(Splitter.on(',').trimResults().omitEmptyStrings()
                    .splitToList(cassandraContactPoints));
        }
        String cassandraKeyspace = props.getProperty("cassandra.keyspace");
        if (!Strings.isNullOrEmpty(cassandraKeyspace)) {
            builder.cassandraKeyspace(cassandraKeyspace);
        }
        String grpcPortText = props.getProperty("grpc.port");
        if (!Strings.isNullOrEmpty(grpcPortText)) {
            builder.grpcPort(Integer.parseInt(grpcPortText));
        }
        return builder.build();
    }

    @Value.Immutable
    static abstract class ServerConfiguration {
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
        int grpcPort() {
            return 8181;
        }
    }

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNecessary() throws Exception {}
    }
}
