/*
 * Copyright 2011-2017 the original author or authors.
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
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.immutables.builder.Builder;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.EnvironmentRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;

import static com.google.common.base.Preconditions.checkNotNull;

public class UiModule {

    // LazyHttpServer is non-null when using netty
    private final @Nullable LazyHttpServer lazyHttpServer;

    // CommonHandler is non-null when using servlet container (applies to central only)
    private final @Nullable CommonHandler commonHandler;

    @Builder.Factory
    public static UiModule createUiModule(
            boolean central,
            boolean servlet,
            boolean offline,
            File baseDir,
            File glowrootDir,
            @Nullable Ticker ticker, // @Nullable to deal with shading from glowroot server
            Clock clock,
            @Nullable LiveJvmService liveJvmService,
            final ConfigRepository configRepository,
            AgentRepository agentRepository,
            EnvironmentRepository environmentRepository,
            TransactionTypeRepository transactionTypeRepository,
            AggregateRepository aggregateRepository,
            TraceAttributeNameRepository traceAttributeNameRepository,
            TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            RepoAdmin repoAdmin,
            RollupLevelService rollupLevelService,
            LiveTraceRepository liveTraceRepository,
            LiveAggregateRepository liveAggregateRepository,
            @Nullable LiveWeavingService liveWeavingService,
            int numWorkerThreads,
            String version) throws Exception {

        LayoutService layoutService =
                new LayoutService(central, servlet, offline, version, configRepository,
                        agentRepository, transactionTypeRepository, traceAttributeNameRepository);
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(central, offline, configRepository, clock, layoutService);
        IndexHtmlHttpService indexHtmlHttpService = new IndexHtmlHttpService(layoutService);
        TransactionCommonService transactionCommonService = new TransactionCommonService(
                aggregateRepository, liveAggregateRepository, configRepository, clock);
        TraceCommonService traceCommonService =
                new TraceCommonService(traceRepository, liveTraceRepository, agentRepository);
        TransactionJsonService transactionJsonService =
                new TransactionJsonService(transactionCommonService, aggregateRepository,
                        configRepository, rollupLevelService, clock);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceRepository,
                liveTraceRepository, configRepository, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        TraceExportHttpService traceExportHttpService =
                new TraceExportHttpService(traceCommonService, version);
        GlowrootLogHttpService glowrootLogHttpService = new GlowrootLogHttpService(baseDir);
        ErrorCommonService errorCommonService =
                new ErrorCommonService(aggregateRepository, liveAggregateRepository);
        ErrorJsonService errorJsonService = new ErrorJsonService(errorCommonService,
                transactionCommonService, traceRepository, rollupLevelService, clock);
        ReportJsonService reportJsonService =
                new ReportJsonService(aggregateRepository, agentRepository, gaugeValueRepository);
        ConfigJsonService configJsonService =
                new ConfigJsonService(agentRepository, configRepository);
        GaugeValueJsonService gaugeValueJsonService = new GaugeValueJsonService(
                gaugeValueRepository, rollupLevelService, agentRepository, configRepository);
        AlertConfigJsonService alertConfigJsonService =
                new AlertConfigJsonService(configRepository);
        AdminJsonService adminJsonService = new AdminJsonService(central, glowrootDir,
                configRepository, repoAdmin, liveAggregateRepository);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(transactionJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(gaugeValueJsonService);
        jsonServices.add(new JvmJsonService(environmentRepository, liveJvmService));
        jsonServices.add(reportJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(new AgentConfigJsonService(configRepository, agentRepository));
        jsonServices.add(new UserConfigJsonService(configRepository));
        jsonServices.add(new RoleConfigJsonService(central, configRepository, agentRepository));
        jsonServices.add(new GaugeConfigJsonService(configRepository, liveJvmService));
        jsonServices.add(new InstrumentationConfigJsonService(configRepository, liveWeavingService,
                liveJvmService));
        jsonServices.add(alertConfigJsonService);
        jsonServices.add(adminJsonService);

        Map<Pattern, HttpService> httpServices = Maps.newHashMap();
        // http services
        httpServices.put(Pattern.compile("^/$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/transaction/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/error/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/jvm/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/report/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/config/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/admin/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/profile/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/login$"), indexHtmlHttpService);
        // export service is not bound under /backend since the export url is visible to users
        // as the download url for the export file
        httpServices.put(Pattern.compile("^/export/trace$"), traceExportHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/entries$"), traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/main-thread-profile$"),
                traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/aux-thread-profile$"),
                traceDetailHttpService);
        httpServices.put(Pattern.compile("^/log$"), glowrootLogHttpService);

        CommonHandler commonHandler = new CommonHandler(layoutService, httpServices,
                httpSessionManager, jsonServices, clock);

        if (servlet) {
            return new UiModule(commonHandler);
        } else {
            String bindAddress = configRepository.getWebConfig().bindAddress();
            int port = configRepository.getWebConfig().port();
            LazyHttpServer lazyHttpServer = new LazyHttpServer(bindAddress, port, configRepository,
                    commonHandler, baseDir, numWorkerThreads);

            lazyHttpServer.init(adminJsonService);
            return new UiModule(lazyHttpServer);
        }
    }

    private UiModule(LazyHttpServer lazyHttpServer) {
        this.lazyHttpServer = lazyHttpServer;
        commonHandler = null;
    }

    private UiModule(CommonHandler commonHandler) {
        this.commonHandler = commonHandler;
        lazyHttpServer = null;
    }

    public int getPort() throws InterruptedException {
        return getPort(checkNotNull(lazyHttpServer).get());
    }

    public CommonHandler getCommonHandler() {
        // only called when using servlet container
        return checkNotNull(commonHandler);
    }

    // used by tests and by central ui
    public void close(boolean waitForChannelClose) throws InterruptedException {
        if (lazyHttpServer != null) {
            HttpServer httpServer = lazyHttpServer.get();
            if (httpServer != null) {
                httpServer.close(waitForChannelClose);
            }
        }
    }

    private static int getPort(@Nullable HttpServer httpServer) {
        return httpServer == null ? -1 : httpServer.getPort();
    }
}
