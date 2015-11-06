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

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.glowroot.central.storage.ConfigDao;
import org.glowroot.central.storage.ConfigRepositoryImpl;
import org.glowroot.common.live.LiveAggregateRepository.LiveAggregateRepositoryNop;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Version;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.simplerepo.SimpleRepoModule;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;

public class Main {

    public static void main(String[] args) throws Exception {

        File dataDir = new File("data");

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Glowroot-Background-%d").build();
        ScheduledExecutorService scheduledExecutor =
                Executors.newScheduledThreadPool(2, threadFactory);

        Clock clock = Clock.systemClock();
        Ticker ticker = Ticker.systemTicker();

        DataSource dataSource = DataSource.createPostgres();
        ConfigDao configDao = new ConfigDao(dataSource);
        ConfigRepository configRepository = new ConfigRepositoryImpl(configDao);
        SimpleRepoModule simpleRepoModule = new SimpleRepoModule(dataSource, dataDir, clock, ticker,
                configRepository, scheduledExecutor, false);

        String version = Version.getVersion();

        new GrpcServer(8181, simpleRepoModule.getServerRepository(),
                simpleRepoModule.getAggregateRepository(),
                simpleRepoModule.getGaugeValueRepository(), simpleRepoModule.getTraceRepository());

        UiModule uiModule = new CreateUiModuleBuilder()
                .central(true)
                .ticker(ticker)
                .clock(clock)
                .liveJvmService(null)
                .configRepository(simpleRepoModule.getConfigRepository())
                .serverRepository(simpleRepoModule.getServerRepository())
                .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                .traceRepository(simpleRepoModule.getTraceRepository())
                .aggregateRepository(simpleRepoModule.getAggregateRepository())
                .gaugeValueRepository(simpleRepoModule.getGaugeValueRepository())
                .repoAdmin(simpleRepoModule.getRepoAdmin())
                .liveTraceRepository(new LiveTraceRepositoryNop())
                .liveAggregateRepository(new LiveAggregateRepositoryNop())
                .liveWeavingService(null)
                .viewerMode(false)
                .bindAddress("0.0.0.0")
                .version(version)
                .build();

        Thread.sleep(Long.MAX_VALUE);
    }
}
