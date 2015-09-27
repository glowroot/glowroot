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
package org.glowroot.server.ui;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import org.immutables.builder.Builder;

import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.live.LiveAggregateRepository;
import org.glowroot.live.LiveJvmService;
import org.glowroot.live.LiveThreadDumpService;
import org.glowroot.live.LiveTraceRepository;
import org.glowroot.live.LiveWeavingService;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.GaugeValueRepository;
import org.glowroot.server.repo.RepoAdmin;
import org.glowroot.server.repo.TraceRepository;
import org.glowroot.server.util.MailService;

public class UiModule {

    private final LazyHttpServer lazyHttpServer;

    @Builder.Factory
    public static UiModule createUiModule(
            Ticker ticker,
            Clock clock,
            File baseDir,
            LiveJvmService liveJvmService,
            ConfigRepository configRepository,
            TraceRepository traceRepository,
            AggregateRepository aggregateRepository,
            GaugeValueRepository gaugeValueRepository,
            RepoAdmin repoAdmin,
            LiveTraceRepository liveTraceRepository,
            LiveThreadDumpService liveThreadDumpService,
            LiveAggregateRepository liveAggregateRepository,
            LiveWeavingService liveWeavingService,
            String bindAddress,
            String version,
            List<PluginDescriptor> pluginDescriptors) { // TODO UI should not depend on this

        LayoutService layoutService =
                new LayoutService(version, configRepository, pluginDescriptors);
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(configRepository, clock, layoutService);
        IndexHtmlHttpService indexHtmlHttpService =
                new IndexHtmlHttpService(httpSessionManager, layoutService);
        LayoutHttpService layoutHttpService =
                new LayoutHttpService(httpSessionManager, layoutService);
        TransactionCommonService transactionCommonService = new TransactionCommonService(
                aggregateRepository, liveAggregateRepository, configRepository);
        TraceCommonService traceCommonService =
                new TraceCommonService(traceRepository, liveTraceRepository);
        TransactionJsonService transactionJsonService =
                new TransactionJsonService(transactionCommonService, traceRepository,
                        liveTraceRepository, aggregateRepository, clock);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceRepository,
                liveTraceRepository, configRepository, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        TraceExportHttpService traceExportHttpService =
                new TraceExportHttpService(traceCommonService, version);
        GlowrootLogHttpService glowrootLogHttpService = new GlowrootLogHttpService(baseDir);
        ErrorCommonService errorCommonService = new ErrorCommonService(aggregateRepository,
                liveAggregateRepository, configRepository.getRollupConfigs());
        ErrorJsonService errorJsonService = new ErrorJsonService(errorCommonService,
                traceRepository, aggregateRepository, clock);
        JvmJsonService jvmJsonService = new JvmJsonService(gaugeValueRepository, configRepository,
                liveJvmService, liveThreadDumpService, clock);
        ConfigJsonService configJsonService = new ConfigJsonService(configRepository, repoAdmin,
                pluginDescriptors, httpSessionManager, new MailService(), liveWeavingService);
        InstrumentationJsonService instrumentationJsonService =
                new InstrumentationJsonService(configRepository, liveWeavingService);
        GaugeJsonService gaugeJsonService = new GaugeJsonService(configRepository, liveJvmService);
        AlertJsonService alertJsonService = new AlertJsonService(configRepository);
        AdminJsonService adminJsonService = new AdminJsonService(aggregateRepository,
                traceRepository, gaugeValueRepository, liveAggregateRepository, configRepository,
                liveWeavingService, liveTraceRepository, repoAdmin);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(transactionJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(jvmJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(instrumentationJsonService);
        jsonServices.add(gaugeJsonService);
        jsonServices.add(alertJsonService);
        jsonServices.add(adminJsonService);

        int port = configRepository.getUserInterfaceConfig().port();
        LazyHttpServer lazyHttpServer = new LazyHttpServer(bindAddress, port, httpSessionManager,
                indexHtmlHttpService, layoutHttpService, layoutService, traceDetailHttpService,
                traceExportHttpService, glowrootLogHttpService, jsonServices);

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
