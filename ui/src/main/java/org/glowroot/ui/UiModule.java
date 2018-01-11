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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.immutables.builder.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.EmbeddedWebConfig;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.EnvironmentRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.SyntheticResultRepository;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.repo.util.HttpClient;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;

import static com.google.common.base.Preconditions.checkNotNull;

public class UiModule {

    private static final Logger logger = LoggerFactory.getLogger(UiModule.class);

    // non-null when using netty
    private final @Nullable HttpServer httpServer;

    // CommonHandler is non-null when using servlet container (applies to central only)
    private final @Nullable CommonHandler commonHandler;

    @Builder.Factory
    public static UiModule createUiModule(
            boolean central,
            boolean servlet,
            boolean offline,
            @Nullable String bindAddress, // only used for central
            @Nullable Integer port, // only used for central
            @Nullable Boolean https, // only used for central
            @Nullable String contextPath, // only used for central
            File confDir,
            @Nullable File sharedConfDir,
            File logDir,
            Pattern logFileNamePattern,
            @Nullable Ticker ticker, // @Nullable to deal with shading from glowroot server
            Clock clock,
            @Nullable LiveJvmService liveJvmService,
            final ConfigRepository configRepository,
            AgentRollupRepository agentRollupRepository,
            EnvironmentRepository environmentRepository,
            TransactionTypeRepository transactionTypeRepository,
            AggregateRepository aggregateRepository,
            TraceAttributeNameRepository traceAttributeNameRepository,
            TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            @Nullable SyntheticResultRepository syntheticResultRepository, // null for embedded
            IncidentRepository incidentRepository,
            RepoAdmin repoAdmin,
            RollupLevelService rollupLevelService,
            LiveTraceRepository liveTraceRepository,
            LiveAggregateRepository liveAggregateRepository,
            @Nullable LiveWeavingService liveWeavingService,
            SessionMapFactory sessionMapFactory,
            HttpClient httpClient,
            int numWorkerThreads,
            String version) throws Exception {

        TransactionCommonService transactionCommonService = new TransactionCommonService(
                aggregateRepository, liveAggregateRepository, configRepository, clock);
        TraceCommonService traceCommonService =
                new TraceCommonService(traceRepository, liveTraceRepository, agentRollupRepository);
        ErrorCommonService errorCommonService =
                new ErrorCommonService(aggregateRepository, liveAggregateRepository);
        MailService mailService = new MailService();

        AdminJsonService adminJsonService =
                new AdminJsonService(central, offline, confDir, sharedConfDir, configRepository,
                        repoAdmin, liveAggregateRepository, mailService, httpClient);

        LayoutService layoutService = new LayoutService(central, offline, version, configRepository,
                transactionTypeRepository, traceAttributeNameRepository, agentRollupRepository);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(new LayoutJsonService(agentRollupRepository, layoutService));
        jsonServices.add(new TransactionJsonService(transactionCommonService, aggregateRepository,
                configRepository, rollupLevelService, clock));
        jsonServices.add(new TracePointJsonService(traceRepository, liveTraceRepository,
                configRepository, ticker, clock));
        jsonServices.add(new TraceJsonService(traceCommonService));
        jsonServices.add(new ErrorJsonService(errorCommonService, transactionCommonService,
                traceRepository, rollupLevelService, clock));
        jsonServices.add(new GaugeValueJsonService(gaugeValueRepository, rollupLevelService,
                configRepository));
        jsonServices
                .add(new JvmJsonService(environmentRepository, configRepository, liveJvmService));
        jsonServices.add(new IncidentJsonService(central, incidentRepository, configRepository,
                agentRollupRepository, clock));
        jsonServices.add(new ReportJsonService(agentRollupRepository, transactionTypeRepository,
                aggregateRepository, gaugeValueRepository, rollupLevelService));
        jsonServices.add(new ConfigJsonService(gaugeValueRepository, configRepository));
        jsonServices
                .add(new AlertConfigJsonService(configRepository, gaugeValueRepository, central));
        jsonServices.add(new UserConfigJsonService(configRepository));
        jsonServices
                .add(new RoleConfigJsonService(central, configRepository, agentRollupRepository));
        jsonServices.add(new GaugeConfigJsonService(configRepository, liveJvmService));
        jsonServices.add(new InstrumentationConfigJsonService(central, configRepository,
                liveWeavingService, liveJvmService));
        jsonServices.add(adminJsonService);

        if (central) {
            checkNotNull(syntheticResultRepository);
            jsonServices.add(new SyntheticResultJsonService(syntheticResultRepository,
                    rollupLevelService, configRepository));
            jsonServices.add(new SyntheticMonitorConfigJsonService(configRepository));
        }

        HttpSessionManager httpSessionManager = new HttpSessionManager(central, offline,
                configRepository, clock, layoutService, sessionMapFactory);
        IndexHtmlHttpService indexHtmlHttpService = new IndexHtmlHttpService(layoutService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        TraceExportHttpService traceExportHttpService =
                new TraceExportHttpService(traceCommonService, version);
        GlowrootLogHttpService glowrootLogHttpService =
                new GlowrootLogHttpService(logDir, logFileNamePattern);

        Map<Pattern, HttpService> httpServices = Maps.newHashMap();
        // http services
        httpServices.put(Pattern.compile("^/$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/transaction/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/error/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/jvm/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/incidents$"), indexHtmlHttpService);
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

        if (central) {
            httpServices.put(Pattern.compile("^/synthetic-monitors$"), indexHtmlHttpService);
        }

        CommonHandler commonHandler = new CommonHandler(central, layoutService, httpServices,
                httpSessionManager, jsonServices, clock);

        if (servlet) {
            return new UiModule(commonHandler);
        } else {
            HttpServer httpServer;
            int initialPort;
            if (central) {
                httpServer = new HttpServer(checkNotNull(bindAddress), checkNotNull(https),
                        Suppliers.ofInstance(checkNotNull(contextPath)), numWorkerThreads,
                        commonHandler, confDir, sharedConfDir, central);
                initialPort = checkNotNull(port);
            } else {
                final EmbeddedWebConfig initialWebConfig = configRepository.getEmbeddedWebConfig();
                Supplier<String> contextPathSupplier = new Supplier<String>() {
                    @Override
                    public String get() {
                        try {
                            return configRepository.getEmbeddedWebConfig().contextPath();
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                            return initialWebConfig.contextPath();
                        }
                    }
                };
                httpServer = new HttpServer(initialWebConfig.bindAddress(),
                        initialWebConfig.https(), contextPathSupplier, numWorkerThreads,
                        commonHandler, confDir, sharedConfDir, central);
                initialPort = initialWebConfig.port();
            }
            adminJsonService.setHttpServer(httpServer);
            httpServer.bindEventually(initialPort);
            return new UiModule(httpServer);
        }
    }

    private UiModule(HttpServer httpServer) {
        this.httpServer = httpServer;
        commonHandler = null;
    }

    private UiModule(CommonHandler commonHandler) {
        this.commonHandler = commonHandler;
        httpServer = null;
    }

    public CommonHandler getCommonHandler() {
        // only called when using servlet container
        return checkNotNull(commonHandler);
    }

    // used by tests and by central ui
    public void close() {
        if (httpServer != null) {
            httpServer.close();
        }
    }
}
