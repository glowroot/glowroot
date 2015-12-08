/*
 * Copyright 2011-2015 the original author or authors.
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

import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.util.MailService;

public class UiModule {

    private final LazyHttpServer lazyHttpServer;

    @Builder.Factory
    public static UiModule createUiModule(
            boolean central,
            @Nullable Ticker ticker, // @Nullable to deal with shading from central
            Clock clock,
            @Nullable File logDir,
            @Nullable LiveJvmService liveJvmService,
            ConfigRepository configRepository,
            ServerRepository serverRepository,
            TransactionTypeRepository transactionTypeRepository,
            AggregateRepository aggregateRepository,
            TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            RepoAdmin repoAdmin,
            RollupLevelService rollupLevelService,
            LiveTraceRepository liveTraceRepository,
            @Nullable LiveWeavingService liveWeavingService,
            String bindAddress,
            int numWorkerThreads,
            String version,
            List<PluginDescriptor> pluginDescriptors) throws Exception {

        LayoutService layoutService = new LayoutService(central, version, configRepository,
                serverRepository, transactionTypeRepository);
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(configRepository, clock, layoutService);
        IndexHtmlHttpService indexHtmlHttpService =
                new IndexHtmlHttpService(httpSessionManager, layoutService);
        LayoutHttpService layoutHttpService =
                new LayoutHttpService(httpSessionManager, layoutService);
        TransactionCommonService transactionCommonService =
                new TransactionCommonService(aggregateRepository, configRepository);
        TraceCommonService traceCommonService =
                new TraceCommonService(traceRepository, liveTraceRepository);
        TransactionJsonService transactionJsonService =
                new TransactionJsonService(transactionCommonService, aggregateRepository,
                        traceRepository, liveTraceRepository, rollupLevelService, clock);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceRepository,
                liveTraceRepository, configRepository, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        TraceExportHttpService traceExportHttpService =
                new TraceExportHttpService(traceCommonService, version);
        GlowrootLogHttpService glowrootLogHttpService;
        if (logDir == null) {
            glowrootLogHttpService = null;
        } else {
            glowrootLogHttpService = new GlowrootLogHttpService(logDir);
        }
        ErrorCommonService errorCommonService = new ErrorCommonService(aggregateRepository);
        ErrorJsonService errorJsonService = new ErrorJsonService(errorCommonService,
                transactionCommonService, traceRepository, rollupLevelService, clock);
        ConfigJsonService configJsonService = new ConfigJsonService(configRepository, repoAdmin,
                pluginDescriptors, httpSessionManager, new MailService(), liveWeavingService);
        GaugeValueJsonService gaugeValueJsonService = new GaugeValueJsonService(
                gaugeValueRepository, rollupLevelService, configRepository);
        AlertConfigJsonService alertJsonService = new AlertConfigJsonService(configRepository);
        AdminJsonService adminJsonService = new AdminJsonService(aggregateRepository,
                traceRepository, gaugeValueRepository, liveWeavingService, repoAdmin);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(transactionJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(gaugeValueJsonService);
        jsonServices.add(new JvmJsonService(serverRepository, liveJvmService));
        if (liveJvmService != null) {
            jsonServices.add(new GaugeConfigJsonService(configRepository, liveJvmService));
        }
        if (liveWeavingService != null) {
            jsonServices.add(
                    new InstrumentationConfigJsonService(configRepository, liveWeavingService));
        }
        jsonServices.add(alertJsonService);
        jsonServices.add(adminJsonService);

        int port = configRepository.getUserInterfaceConfig().port();
        LazyHttpServer lazyHttpServer = new LazyHttpServer(bindAddress, port, httpSessionManager,
                indexHtmlHttpService, layoutHttpService, layoutService, traceDetailHttpService,
                traceExportHttpService, glowrootLogHttpService, jsonServices, numWorkerThreads);

        lazyHttpServer.init(configJsonService);
        return new UiModule(lazyHttpServer);
    }

    private UiModule(LazyHttpServer lazyHttpServer) {
        this.lazyHttpServer = lazyHttpServer;
    }

    public int getPort() throws InterruptedException {
        return getPort(lazyHttpServer.get());
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        HttpServer httpServer = lazyHttpServer.get();
        if (httpServer != null) {
            httpServer.close();
        }
    }

    private static int getPort(@Nullable HttpServer httpServer) {
        return httpServer == null ? -1 : httpServer.getPort();
    }
}
