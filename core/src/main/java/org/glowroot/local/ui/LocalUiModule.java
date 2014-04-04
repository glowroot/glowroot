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
import java.util.Map;
import java.util.regex.Pattern;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.jboss.netty.channel.ChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.CollectorModule;
import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.ui.HttpServer.PortChangeFailedException;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.TraceModule;
import org.glowroot.trace.TraceRegistry;
import org.glowroot.weaving.ParsedTypeCache;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    @Nullable
    private static final Integer port;

    static {
        // this is used for the demo site so there can be a standby instance on a different port
        port = Integer.getInteger("glowroot.internal.ui.port");
    }

    private final TraceExportHttpService traceExportHttpService;
    // httpServer is only null if it could not even bind to port 0 (any available port)
    @Nullable
    private final HttpServer httpServer;

    public LocalUiModule(Ticker ticker, Clock clock, File dataDir, JvmModule jvmModule,
            ConfigModule configModule, StorageModule storageModule,
            CollectorModule collectorModule, TraceModule traceModule,
            @Nullable Instrumentation instrumentation, @ReadOnly Map<String, String> properties,
            String version) {

        ConfigService configService = configModule.getConfigService();
        PluginDescriptorCache pluginDescriptorCache = configModule.getPluginDescriptorCache();

        AggregateDao aggregateDao = storageModule.getAggregateDao();
        SnapshotDao snapshotDao = storageModule.getSnapshotDao();
        DataSource dataSource = storageModule.getDataSource();
        CappedDatabase cappedDatabase = storageModule.getCappedDatabase();
        TraceCollectorImpl traceCollector = collectorModule.getTraceCollector();
        ParsedTypeCache parsedTypeCache = traceModule.getParsedTypeCache();

        TraceRegistry traceRegistry = traceModule.getTraceRegistry();

        LayoutJsonService layoutJsonService = new LayoutJsonService(version, configService,
                pluginDescriptorCache, jvmModule.getHeapHistograms().getService(),
                jvmModule.getHeapDumps().getService(),
                jvmModule.getDiagnosticOptions().getService(),
                collectorModule.getFixedAggregationIntervalSeconds());
        HttpSessionManager httpSessionManager = new HttpSessionManager(configService, clock,
                layoutJsonService);
        String baseHref = getBaseHref(properties);
        IndexHtmlService indexHtmlService =
                new IndexHtmlService(baseHref, httpSessionManager, layoutJsonService);
        HomeJsonService homeJsonService = new HomeJsonService(storageModule.getAggregateDao(),
                collectorModule.getFixedAggregationIntervalSeconds());
        TraceCommonService traceCommonService =
                new TraceCommonService(snapshotDao, traceRegistry, traceCollector, clock, ticker);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(snapshotDao,
                traceRegistry, traceCollector, ticker, clock);
        TraceSummaryJsonService traceSummaryJsonService =
                new TraceSummaryJsonService(traceCommonService);
        SnapshotHttpService snapshotHttpService = new SnapshotHttpService(traceCommonService);
        traceExportHttpService = new TraceExportHttpService(traceCommonService);
        ErrorJsonService errorJsonService = new ErrorJsonService(snapshotDao);
        JvmJsonService jvmJsonService = new JvmJsonService(jvmModule.getThreadAllocatedBytes(),
                jvmModule.getHeapHistograms(), jvmModule.getHeapDumps(),
                jvmModule.getDiagnosticOptions());
        ConfigJsonService configJsonService = new ConfigJsonService(configService, cappedDatabase,
                pluginDescriptorCache, dataDir, traceModule.getPointcutConfigAdviceCache(),
                httpSessionManager, traceModule);
        ClasspathCache classpathCache = new ClasspathCache(parsedTypeCache);
        PointcutConfigJsonService pointcutConfigJsonService =
                new PointcutConfigJsonService(parsedTypeCache, classpathCache);
        AdminJsonService adminJsonService = new AdminJsonService(aggregateDao, snapshotDao,
                configService, traceModule.getPointcutConfigAdviceCache(), parsedTypeCache,
                instrumentation, traceCollector, dataSource, traceRegistry);

        ImmutableList.Builder<Object> jsonServices = ImmutableList.builder();
        jsonServices.add(layoutJsonService);
        jsonServices.add(homeJsonService);
        jsonServices.add(tracePointJsonService);
        jsonServices.add(traceSummaryJsonService);
        jsonServices.add(errorJsonService);
        jsonServices.add(jvmJsonService);
        jsonServices.add(configJsonService);
        jsonServices.add(pointcutConfigJsonService);
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
                indexHtmlService, snapshotHttpService, traceExportHttpService,
                jsonServices.build());
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
    public TraceExportHttpService getTraceExportHttpService() {
        return traceExportHttpService;
    }

    @OnlyUsedByTests
    public void changeHttpServerPort(int newPort) throws PortChangeFailedException {
        if (httpServer != null) {
            httpServer.changePort(newPort);
        }
    }

    private static String getBaseHref(@ReadOnly Map<String, String> properties) {
        // empty check to support parameterized script, e.g. -Dglowroot.ui.base=${somevar}
        String baseHref = properties.get("ui.base");
        if (Strings.isNullOrEmpty(baseHref)) {
            return "/";
        } else {
            return baseHref;
        }
    }

    private static String getBindAddress(@ReadOnly Map<String, String> properties) {
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
            HttpSessionManager httpSessionManager, IndexHtmlService indexHtmlService,
            SnapshotHttpService snapshotHttpService, TraceExportHttpService traceExportHttpService,
            ImmutableList<Object> jsonServices) {

        String resourceBase = "org/glowroot/local/ui/app-dist";

        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/home$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/traces$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/errors$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/jvm/.*$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/config/.*$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/plugin/.*$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/login$"), resourceBase + "/index.html");
        // internal resources
        uriMappings.put(Pattern.compile("^/scripts/(.*)$"), resourceBase + "/scripts/$1");
        uriMappings.put(Pattern.compile("^/styles/(.*)$"), resourceBase + "/styles/$1");
        uriMappings.put(Pattern.compile("^/favicon\\.ico$"), resourceBase + "/favicon.ico");
        uriMappings.put(Pattern.compile("^/bower_components/(.*)$"),
                resourceBase + "/bower_components/$1");
        uriMappings.put(Pattern.compile("^/sources/(.*)$"), resourceBase + "/sources/$1");
        // services
        // export service is not bound under /backend since the export url is visible to users as
        // the download url for the export file
        uriMappings.put(Pattern.compile("^/export/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/detail/.*$"), snapshotHttpService);
        try {
            return new HttpServer(bindAddress, port, numWorkerThreads, indexHtmlService,
                    uriMappings.build(), httpSessionManager, jsonServices);
        } catch (ChannelException e) {
            // binding to the specified port failed and binding to port 0 (any port) failed
            logger.error("error binding to any port, the user interface will not be available", e);
            return null;
        }
    }
}
