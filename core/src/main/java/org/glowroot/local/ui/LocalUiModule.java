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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jboss.netty.channel.ChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.CollectorModule;
import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.ui.HttpServer.PortChangeFailedException;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.transaction.TransactionModule;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.weaving.AnalyzedWorld;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    // this is used for the demo site so there can be a standby instance on a different port
    @Nullable
    private static final Integer port = Integer.getInteger("glowroot.internal.ui.port");

    // httpServer is only null if it could not even bind to port 0 (any available port)
    @Nullable
    private final HttpServer httpServer;

    // only stored/exposed for tests
    private final AggregateCommonService aggregateCommonService;
    // only stored/exposed for tests
    private final AggregateExportHttpService transactionExportHttpService;
    // only stored/exposed for tests
    private final TraceCommonService traceCommonService;
    // only stored/exposed for tests
    private final TraceExportHttpService traceExportHttpService;

    public LocalUiModule(Ticker ticker, Clock clock, File dataDir, JvmModule jvmModule,
            ConfigModule configModule, StorageModule storageModule,
            CollectorModule collectorModule, TransactionModule traceModule,
            @Nullable Instrumentation instrumentation, Map<String, String> properties,
            String version) {

        ConfigService configService = configModule.getConfigService();
        PluginDescriptorCache pluginDescriptorCache = configModule.getPluginDescriptorCache();

        AggregateDao aggregateDao = storageModule.getAggregateDao();
        TraceDao traceDao = storageModule.getTraceDao();
        DataSource dataSource = storageModule.getDataSource();
        CappedDatabase cappedDatabase = storageModule.getCappedDatabase();
        TransactionCollectorImpl transactionCollector = collectorModule.getTransactionCollector();
        AnalyzedWorld analyzedWorld = traceModule.getAnalyzedWorld();

        TransactionRegistry transactionRegistry = traceModule.getTraceRegistry();

        LayoutJsonService layoutJsonService = new LayoutJsonService(version, configService,
                pluginDescriptorCache, jvmModule.getHeapHistograms(), jvmModule.getHeapDumps(),
                collectorModule.getFixedAggregateIntervalSeconds());
        HttpSessionManager httpSessionManager =
                new HttpSessionManager(configService, clock, layoutJsonService);
        IndexHtmlHttpService indexHtmlHttpService =
                new IndexHtmlHttpService(httpSessionManager, layoutJsonService);
        aggregateCommonService = new AggregateCommonService(aggregateDao);
        traceCommonService = new TraceCommonService(traceDao, transactionRegistry,
                transactionCollector, clock, ticker);
        AggregateJsonService aggregateJsonService = new AggregateJsonService(
                aggregateCommonService, storageModule.getAggregateDao(), clock,
                collectorModule.getFixedAggregateIntervalSeconds());
        TracePointJsonService tracePointJsonService = new TracePointJsonService(traceDao,
                transactionRegistry, transactionCollector, ticker, clock);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        TraceDetailHttpService traceDetailHttpService =
                new TraceDetailHttpService(traceCommonService);
        transactionExportHttpService = new AggregateExportHttpService(aggregateCommonService);
        traceExportHttpService = new TraceExportHttpService(traceCommonService);
        ErrorJsonService errorJsonService = new ErrorJsonService(traceDao);
        JvmJsonService jvmJsonService = new JvmJsonService(jvmModule.getLazyPlatformMBeanServer(),
                jvmModule.getThreadAllocatedBytes(), jvmModule.getHeapHistograms(),
                jvmModule.getHeapDumps());
        ConfigJsonService configJsonService = new ConfigJsonService(configService, cappedDatabase,
                pluginDescriptorCache, dataDir, httpSessionManager, traceModule);
        ClasspathCache classpathCache = new ClasspathCache(analyzedWorld);
        CapturePointJsonService capturePointJsonService = new CapturePointJsonService(
                configService, traceModule.getAdviceCache(), classpathCache, traceModule);
        AdminJsonService adminJsonService = new AdminJsonService(aggregateDao, traceDao,
                configService, traceModule.getAdviceCache(), analyzedWorld,
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
        jsonServices.add(adminJsonService);

        // for now only a single http worker thread to keep # of threads down
        final int numWorkerThreads = 1;
        int port;
        if (LocalUiModule.port == null) {
            port = configService.getUserInterfaceConfig().getPort();
        } else {
            port = LocalUiModule.port;
        }
        String bindAddress = getBindAddress(properties);
        httpServer = buildHttpServer(bindAddress, port, numWorkerThreads, httpSessionManager,
                indexHtmlHttpService, layoutJsonService, traceDetailHttpService,
                transactionExportHttpService, traceExportHttpService, jsonServices);
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
        logger.debug("close()");
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

    @Nullable
    private static HttpServer buildHttpServer(String bindAddress, int port, int numWorkerThreads,
            HttpSessionManager httpSessionManager, IndexHtmlHttpService indexHtmlHttpService,
            LayoutJsonService layoutJsonService, TraceDetailHttpService traceDetailHttpService,
            AggregateExportHttpService aggregateExportHttpService,
            TraceExportHttpService traceExportHttpService, List<Object> jsonServices) {

        String resourceBase = "org/glowroot/local/ui/app-dist";

        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/performance$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/errors$"), indexHtmlHttpService);
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
        // export services are not bound under /backend since the export urls are visible to users
        // as the download url for the export file
        uriMappings.put(Pattern.compile("^/export/performance.*$"), aggregateExportHttpService);
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
