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
import org.apache.shiro.authc.credential.PasswordMatcher;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.immutables.builder.Builder;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.util.MailService;

public class UiModule {

    private final LazyHttpServer lazyHttpServer;

    @Builder.Factory
    public static UiModule createUiModule(
            boolean fat,
            @Nullable Ticker ticker, // @Nullable to deal with shading from glowroot server
            Clock clock,
            File logDir,
            @Nullable LiveJvmService liveJvmService,
            ConfigRepository configRepository,
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
            String bindAddress,
            int numWorkerThreads,
            String version) throws Exception {

        AuthorizingRealm realm = new GlowrootRealm(configRepository);
        realm.setAuthorizationCachingEnabled(false);
        PasswordMatcher passwordMatcher = new PasswordMatcher();
        realm.setCredentialsMatcher(passwordMatcher);
        PasswordService passwordService = passwordMatcher.getPasswordService();

        LayoutService layoutService = new LayoutService(fat, version, configRepository,
                agentRepository, transactionTypeRepository);
        SecurityManager securityManager = new DefaultSecurityManager(realm);
        SessionHelper sessionHelper = new SessionHelper(configRepository, layoutService);
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
                gaugeValueRepository, rollupLevelService, configRepository);
        AlertConfigJsonService alertJsonService = new AlertConfigJsonService(configRepository);
        AdminJsonService adminJsonService = new AdminJsonService(fat, configRepository, repoAdmin,
                new MailService(), passwordService, aggregateRepository,
                traceRepository, transactionTypeRepository, gaugeValueRepository);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(transactionJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(new UserConfigJsonService(configRepository, passwordService));
        jsonServices.add(new RoleConfigJsonService(fat, configRepository, agentRepository));
        jsonServices.add(gaugeValueJsonService);
        jsonServices.add(new JvmJsonService(agentRepository, liveJvmService));
        if (liveJvmService != null) {
            jsonServices.add(new GaugeConfigJsonService(configRepository, liveJvmService));
        }
        if (liveWeavingService != null && liveJvmService != null) {
            jsonServices.add(new InstrumentationConfigJsonService(configRepository,
                    liveWeavingService, liveJvmService));
        }
        jsonServices.add(alertJsonService);
        jsonServices.add(adminJsonService);

        int port = configRepository.getWebConfig().port();
        LazyHttpServer lazyHttpServer = new LazyHttpServer(bindAddress, port, securityManager,
                sessionHelper, indexHtmlHttpService, layoutHttpService, layoutService,
                traceDetailHttpService, traceExportHttpService, glowrootLogHttpService,
                jsonServices, numWorkerThreads);

        lazyHttpServer.init(adminJsonService);
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
