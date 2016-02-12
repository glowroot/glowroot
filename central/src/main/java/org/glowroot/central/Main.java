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
import java.util.List;
import java.util.Properties;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.glowroot.central.storage.AggregateDao;
import org.glowroot.central.storage.AlertConfigDao;
import org.glowroot.central.storage.CentralConfigDao;
import org.glowroot.central.storage.ConfigRepositoryImpl;
import org.glowroot.central.storage.GaugeValueDao;
import org.glowroot.central.storage.ServerDao;
import org.glowroot.central.storage.TraceDao;
import org.glowroot.central.storage.TransactionTypeDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.ui.CreateUiModuleBuilder;

public class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {

        // install jul-to-slf4j bridge for protobuf which logs to jul
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        Clock clock = Clock.systemClock();
        String version = Version.getVersion(Main.class);

        List<String> contactPoints = getCassandraContactPoints();
        Cluster cluster = Cluster.builder()
                .addContactPoints(contactPoints.toArray(new String[0]))
                .build();
        Session session = cluster.connect();
        session.execute("create keyspace if not exists glowroot with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot");

        ServerDao serverDao = new ServerDao(session);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session);

        CentralConfigDao centralConfigDao = new CentralConfigDao(session);
        AlertConfigDao alertConfigDao = new AlertConfigDao(session);
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(serverDao, centralConfigDao, alertConfigDao);

        AggregateRepository aggregateRepository =
                new AggregateDao(session, serverDao, transactionTypeDao, configRepository);
        TraceRepository traceRepository = new TraceDao(session, serverDao, transactionTypeDao);
        GaugeValueRepository gaugeValueRepository =
                new GaugeValueDao(session, serverDao, configRepository);

        GrpcServer server = new GrpcServer(8181, serverDao, aggregateRepository,
                gaugeValueRepository, traceRepository);
        configRepository.setDownstreamService(server.getDownstreamService());

        RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);

        new CreateUiModuleBuilder()
                .central(true)
                .clock(clock)
                .logDir(new File("."))
                .liveJvmService(new LiveJvmServiceImpl(server.getDownstreamService()))
                .configRepository(configRepository)
                .serverRepository(serverDao)
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

        Thread.sleep(Long.MAX_VALUE);
    }

    private static List<String> getCassandraContactPoints() throws IOException {
        File propFile = new File("central.properties");
        if (!propFile.exists()) {
            throw new IllegalStateException("Configuration file missing: central.properties");
        }
        Properties props = new Properties();
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        String contactPoints = props.getProperty("cassandra.contact.points");
        if (Strings.isNullOrEmpty(contactPoints)) {
            throw new IllegalStateException("Configuration missing: cassandra.contact.points");
        }
        return Splitter.on(',').trimResults().omitEmptyStrings().splitToList(contactPoints);
    }

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNecessary() throws Exception {}
    }
}
