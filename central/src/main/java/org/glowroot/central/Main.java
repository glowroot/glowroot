/*
 * Copyright 2015 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;

import org.glowroot.central.storage.AggregateDao;
import org.glowroot.central.storage.ConfigDao;
import org.glowroot.central.storage.ConfigRepositoryImpl;
import org.glowroot.central.storage.GaugeValueDao;
import org.glowroot.central.storage.ServerDao;
import org.glowroot.central.storage.TraceDao;
import org.glowroot.central.storage.TransactionTypeDao;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

public class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {

        Clock clock = Clock.systemClock();
        String version = Version.getVersion();

        // FIXME
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
        Session session = cluster.connect();
        // session.execute("drop keyspace if exists glowroot");
        session.execute("create keyspace if not exists glowroot with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot");

        ConfigDao configDao = new ConfigDao(session);
        ConfigRepository configRepository = new ConfigRepositoryImpl(configDao);

        ServerDao serverDao = new ServerDao(session);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session);

        AggregateRepository aggregateRepository =
                new AggregateDao(session, serverDao, transactionTypeDao, configRepository);
        TraceRepository traceRepository = new TraceDao(session, serverDao, transactionTypeDao);
        GaugeValueRepository gaugeValueRepository =
                new GaugeValueDao(session, serverDao, configRepository);

        GrpcServer server = new GrpcServer(8181, serverDao, aggregateRepository,
                gaugeValueRepository, traceRepository);

        RollupLevelService rollupLevelService = new RollupLevelService(configRepository, clock);

        UiModule uiModule = new CreateUiModuleBuilder()
                .central(true)
                .clock(clock)
                .liveJvmService(new LiveJvmServiceImpl(server.getDownstreamService()))
                .configRepository(configRepository)
                .serverRepository(serverDao)
                .transactionTypeRepository(transactionTypeDao)
                .traceRepository(traceRepository)
                .aggregateRepository(aggregateRepository)
                .gaugeValueRepository(gaugeValueRepository)
                .repoAdmin(new NopRepoAdmin())
                .rollupLevelService(rollupLevelService)
                .liveTraceRepository(new LiveTraceRepositoryNop())
                .liveWeavingService(null)
                .bindAddress("0.0.0.0")
                .numWorkerThreads(50)
                .version(version)
                .build();

        Thread.sleep(Long.MAX_VALUE);
    }

    private static class NopRepoAdmin implements RepoAdmin {
        @Override
        public void defrag() throws Exception {}
        @Override
        public void resizeIfNecessary() throws Exception {}
    }
}
