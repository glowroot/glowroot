/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.ui;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import org.immutables.builder.Builder;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;

public class UiModule {

    private final LazyHttpServer lazyHttpServer;

    @Builder.Factory
    public static UiModule createUiModule(
            boolean fat,
            boolean offlineViewer,
            @Nullable Ticker ticker, // @Nullable to deal with shading from glowroot server
            Clock clock,
            File logDir,
            @Nullable LiveJvmService liveJvmService,
            final ConfigRepository configRepository,
            AgentRepository agentRepository,
            TransactionTypeRepository transactionTypeRepository,
            AggregateRepository aggregateRepository,
            TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            RepoAdmin repoAdmin,
            RollupLevelService rollupLevelService,
            LiveTraceRepository liveTraceRepository,
            LiveAggregateRepository liveAggregateRepository,
            @Nullable LiveWeavingService liveWeavingService,
            int numWorkerThreads,
            String version) throws Exception {

        LayoutService layoutService = new LayoutService(fat, offlineViewer, version,
                configRepository, agentRepository, transactionTypeRepository);
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(fat, offlineViewer, configRepository, clock, layoutService);
        IndexHtmlHttpService indexHtmlHttpService = new IndexHtmlHttpService(layoutService);
        LayoutHttpService layoutHttpService = new LayoutHttpService(layoutService);
        TransactionCommonService transactionCommonService = new TransactionCommonService(
                aggregateRepository, liveAggregateRepository, configRepository, clock);
        TraceCommonService traceCommonService =
                new TraceCommonService(traceRepository, liveTraceRepository);
        TransactionJsonService transactionJsonService = new TransactionJsonService(
                transactionCommonService, aggregateRepository, rollupLevelService, clock);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceRepository,
                liveTraceRepository, configRepository, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        TraceExportHttpService traceExportHttpService =
                new TraceExportHttpService(traceCommonService, version);
        GlowrootLogHttpService glowrootLogHttpService = new GlowrootLogHttpService(logDir);
        ErrorCommonService errorCommonService =
                new ErrorCommonService(aggregateRepository, liveAggregateRepository);
        ErrorJsonService errorJsonService = new ErrorJsonService(errorCommonService,
                transactionCommonService, traceRepository, rollupLevelService, clock);
        ConfigJsonService configJsonService = new ConfigJsonService(configRepository);
        GaugeValueJsonService gaugeValueJsonService = new GaugeValueJsonService(
                gaugeValueRepository, rollupLevelService, agentRepository, configRepository);
        AlertConfigJsonService alertJsonService = new AlertConfigJsonService(configRepository);
        AdminJsonService adminJsonService = new AdminJsonService(fat, configRepository, repoAdmin,
                liveAggregateRepository, new MailService());

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(transactionJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(new UserConfigJsonService(configRepository));
        jsonServices.add(new RoleConfigJsonService(fat, configRepository, agentRepository));
        jsonServices.add(gaugeValueJsonService);
        jsonServices.add(new JvmJsonService(agentRepository, liveJvmService));
        jsonServices.add(new GaugeConfigJsonService(configRepository, liveJvmService));
        jsonServices.add(new InstrumentationConfigJsonService(configRepository, liveWeavingService,
                liveJvmService));
        jsonServices.add(alertJsonService);
        jsonServices.add(adminJsonService);

        String bindAddress = configRepository.getWebConfig().bindAddress();
        int port = configRepository.getWebConfig().port();
        LazyHttpServer lazyHttpServer = new LazyHttpServer(bindAddress, port, httpSessionManager,
                indexHtmlHttpService, layoutHttpService, layoutService, traceDetailHttpService,
                traceExportHttpService, glowrootLogHttpService, jsonServices, clock,
                numWorkerThreads);

        lazyHttpServer.init(adminJsonService);
        return new UiModule(lazyHttpServer);
    }

    private UiModule(LazyHttpServer lazyHttpServer) {
        this.lazyHttpServer = lazyHttpServer;
    }

    public int getPort() throws InterruptedException {
        return getPort(lazyHttpServer.get());
    }

    // used by tests and by central ui
    public void close(boolean waitForChannelClose) throws InterruptedException {
        HttpServer httpServer = lazyHttpServer.get();
        if (httpServer != null) {
            httpServer.close(waitForChannelClose);
        }
    }

    private static int getPort(@Nullable HttpServer httpServer) {
        return httpServer == null ? -1 : httpServer.getPort();
    }
}
