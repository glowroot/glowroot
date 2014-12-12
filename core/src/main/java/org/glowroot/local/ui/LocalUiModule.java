/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.jboss.netty.channel.ChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.CollectorModule;
import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.GaugePointDao;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.ui.HttpServer.PortChangeFailedException;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionModule;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.weaving.AnalyzedWorld;

public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    // this is used for the demo site so there can be a standby instance on a different port
    private static final @Nullable Integer port = Integer.getInteger("glowroot.internal.ui.port");

    // httpServer is only null if it could not even bind to port 0 (any available port)
    private final @Nullable HttpServer httpServer;

    // only stored/exposed for tests
    private final AggregateCommonService aggregateCommonService;
    // only stored/exposed for tests
    private final TraceCommonService traceCommonService;
    // only stored/exposed for tests
    private final TraceExportHttpService traceExportHttpService;

    public LocalUiModule(Ticker ticker, Clock clock, File dataDir, JvmModule jvmModule,
            ConfigModule configModule, StorageModule storageModule,
            CollectorModule collectorModule, TransactionModule transactionModule,
            @Nullable Instrumentation instrumentation, Map<String, String> properties,
            String version) {

        ConfigService configService = configModule.getConfigService();

        AggregateDao aggregateDao = storageModule.getAggregateDao();
        TraceDao traceDao = storageModule.getTraceDao();
        GaugePointDao gaugePointDao = storageModule.getGaugePointDao();
        DataSource dataSource = storageModule.getDataSource();
        CappedDatabase cappedDatabase = storageModule.getCappedDatabase();
        TransactionCollectorImpl transactionCollector = collectorModule.getTransactionCollector();
        AnalyzedWorld analyzedWorld = transactionModule.getAnalyzedWorld();

        TransactionRegistry transactionRegistry = transactionModule.getTransactionRegistry();

        LayoutJsonService layoutJsonService = new LayoutJsonService(version, configService,
                configModule.getPluginDescriptors(), jvmModule.getHeapDumps(),
                collectorModule.getFixedAggregateIntervalSeconds(),
                collectorModule.getFixedGaugeIntervalSeconds());
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(configService, clock, layoutJsonService);
        IndexHtmlHttpService indexHtmlHttpService =
                new IndexHtmlHttpService(httpSessionManager, layoutJsonService);
        aggregateCommonService = new AggregateCommonService(aggregateDao);
        traceCommonService = new TraceCommonService(traceDao, transactionRegistry,
                transactionCollector, clock, ticker);
        AggregateJsonService aggregateJsonService = new AggregateJsonService(
                aggregateCommonService, storageModule.getAggregateDao(), traceDao, clock,
                collectorModule.getFixedAggregateIntervalSeconds());
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceDao,
                transactionRegistry, transactionCollector, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        traceExportHttpService = new TraceExportHttpService(traceCommonService);
        ErrorJsonService errorJsonService = new ErrorJsonService(aggregateDao, traceDao, clock,
                collectorModule.getFixedAggregateIntervalSeconds());
        JvmJsonService jvmJsonService = new JvmJsonService(jvmModule.getLazyPlatformMBeanServer(),
                gaugePointDao, configService, jvmModule.getThreadAllocatedBytes(),
                jvmModule.getHeapDumps(), collectorModule.getFixedGaugeIntervalSeconds());
        ConfigJsonService configJsonService = new ConfigJsonService(configService, cappedDatabase,
                configModule.getPluginDescriptors(), dataDir, httpSessionManager,
                transactionModule);
        ClasspathCache classpathCache = new ClasspathCache(analyzedWorld, instrumentation);
        CapturePointJsonService capturePointJsonService = new CapturePointJsonService(
                configService, transactionModule.getAdviceCache(), classpathCache,
                transactionModule);
        GaugeJsonService gaugeJsonService =
                new GaugeJsonService(configService, jvmModule.getLazyPlatformMBeanServer());
        AdminJsonService adminJsonService = new AdminJsonService(aggregateDao, traceDao,
                gaugePointDao, configService, transactionModule.getAdviceCache(), analyzedWorld,
                instrumentation, transactionCollector, dataSource, transactionRegistry);

        List<Object> jsonServices = Lists.newArrayList();
        jsonServices.add(layoutJsonService);
        jsonServices.add(aggregateJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(jvmJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(capturePointJsonService);
        jsonServices.add(gaugeJsonService);
        jsonServices.add(adminJsonService);

        // for now only a single http worker thread to keep # of threads down
        final int numWorkerThreads = 1;
        int port;
        if (LocalUiModule.port == null) {
            port = configService.getUserInterfaceConfig().port();
        } else {
            port = LocalUiModule.port;
        }
        String bindAddress = getBindAddress(properties);
        httpServer = buildHttpServer(bindAddress, port, numWorkerThreads, httpSessionManager,
                indexHtmlHttpService, layoutJsonService, traceDetailHttpService,
                traceExportHttpService, jsonServices);
        if (httpServer != null) {
            configJsonService.setHttpServer(httpServer);
        }
    }

    public int getPort() {
        if (httpServer == null) {
            return -1;
        } else {
            return httpServer.getPort();
        }
    }

    @OnlyUsedByTests
    public void close() {
        if (httpServer != null) {
            httpServer.close();
        }
    }

    @OnlyUsedByTests
    public TraceCommonService getTraceCommonService() {
        return traceCommonService;
    }

    @OnlyUsedByTests
    public TraceExportHttpService getTraceExportHttpService() {
        return traceExportHttpService;
    }

    @OnlyUsedByTests
    public void changeHttpServerPort(int newPort) throws PortChangeFailedException {
        if (httpServer != null) {
            httpServer.changePort(newPort);
        }
    }

    private static String getBindAddress(Map<String, String> properties) {
        // empty check to support parameterized script, e.g. -Dglowroot.ui.bind.address=${somevar}
        String bindAddress = properties.get("ui.bind.address");
        if (Strings.isNullOrEmpty(bindAddress)) {
            return "0.0.0.0";
        } else {
            return bindAddress;
        }
    }

    private static @Nullable HttpServer buildHttpServer(String bindAddress, int port,
            int numWorkerThreads, HttpSessionManager httpSessionManager,
            IndexHtmlHttpService indexHtmlHttpService, LayoutJsonService layoutJsonService,
            TraceDetailHttpService traceDetailHttpService,
            TraceExportHttpService traceExportHttpService, List<Object> jsonServices) {

        String resourceBase = "org/glowroot/local/ui/app-dist";

        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/performance/transactions$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/performance/metrics"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/errors/transactions$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/errors/messages$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/traces$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/jvm/.*$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/config/.*$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/login$"), indexHtmlHttpService);
        // internal resources
        uriMappings.put(Pattern.compile("^/scripts/(.*)$"), resourceBase + "/scripts/$1");
        uriMappings.put(Pattern.compile("^/styles/(.*)$"), resourceBase + "/styles/$1");
        uriMappings.put(Pattern.compile("^/fonts/(.*)$"), resourceBase + "/fonts/$1");
        uriMappings.put(Pattern.compile("^/favicon\\.([0-9a-f]+)\\.ico$"),
                resourceBase + "/favicon.$1.ico");
        uriMappings.put(Pattern.compile("^/sources/(.*)$"), resourceBase + "/sources/$1");
        // services
        // export service is not bound under /backend since the export url is visible to users
        // as the download url for the export file
        uriMappings.put(Pattern.compile("^/export/trace/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/entries$"), traceDetailHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/profile$"), traceDetailHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/outlier-profile$"),
                traceDetailHttpService);
        try {
            return new HttpServer(bindAddress, port, numWorkerThreads, layoutJsonService,
                    uriMappings.build(), httpSessionManager, jsonServices);
        } catch (ChannelException e) {
            // binding to the specified port failed and binding to port 0 (any port) failed
            logger.error("error binding to any port, the user interface will not be available", e);
            return null;
        }
    }
}
