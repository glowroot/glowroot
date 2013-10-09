/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.regex.Pattern;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.jboss.netty.channel.ChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.collector.CollectorModule;
import io.informant.collector.TraceCollectorImpl;
import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.config.PluginDescriptorCache;
import io.informant.local.store.DataSource;
import io.informant.local.store.RollingFile;
import io.informant.local.store.SnapshotDao;
import io.informant.local.store.StorageModule;
import io.informant.local.ui.HttpServerHandler.JsonServiceMapping;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.trace.TraceModule;
import io.informant.trace.TraceRegistry;
import io.informant.weaving.ParsedTypeCache;

import static io.informant.local.ui.HttpServerHandler.HttpMethod.GET;
import static io.informant.local.ui.HttpServerHandler.HttpMethod.POST;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    private static final int DEFAULT_UI_PORT = 4000;

    private final TraceExportHttpService traceExportHttpService;
    @Nullable
    private final HttpServer httpServer;

    public LocalUiModule(Ticker ticker, Clock clock, File dataDir, ConfigModule configModule,
            StorageModule storageModule, CollectorModule collectorModule, TraceModule traceModule,
            @Nullable Instrumentation instrumentation, @ReadOnly Map<String, String> properties,
            String version) {

        ConfigService configService = configModule.getConfigService();
        PluginDescriptorCache pluginDescriptorCache = configModule.getPluginDescriptorCache();

        DataSource dataSource = storageModule.getDataSource();
        RollingFile rollingFile = storageModule.getRollingFile();
        SnapshotDao snapshotDao = storageModule.getSnapshotDao();
        TraceCollectorImpl traceCollector = collectorModule.getTraceCollector();
        ParsedTypeCache parsedTypeCache = traceModule.getParsedTypeCache();

        TraceRegistry traceRegistry = traceModule.getTraceRegistry();

        AggregateJsonService aggregateJsonService =
                new AggregateJsonService(storageModule.getAggregateDao(),
                        collectorModule.getFixedAggregateIntervalSeconds());
        TraceCommonService traceCommonService =
                new TraceCommonService(snapshotDao, traceRegistry, clock, ticker);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(snapshotDao,
                traceRegistry, traceCollector, ticker, clock);
        TraceSummaryJsonService traceSummaryJsonService =
                new TraceSummaryJsonService(traceCommonService);
        SnapshotHttpService snapshotHttpService = new SnapshotHttpService(traceCommonService);
        traceExportHttpService = new TraceExportHttpService(traceCommonService);
        // when port is 0, intentionally passing it as 0 instead of its resolved value since the
        // port is just displayed on config page for its documentation value anyways, and more
        // useful to know it was set to 0 than to display its value (which is needed to view the
        // page anyways)
        ConfigJsonService configJsonService =
                new ConfigJsonService(configService, rollingFile, pluginDescriptorCache, dataDir,
                        traceModule.getDynamicAdviceCache(), instrumentation);
        ClasspathCache classpathCache = new ClasspathCache(parsedTypeCache);
        AdhocPointcutConfigJsonService adhocPointcutConfigJsonService =
                new AdhocPointcutConfigJsonService(parsedTypeCache, classpathCache);
        JvmJsonService jvmJsonService = new JvmJsonService();
        AdminJsonService adminJsonService = new AdminJsonService(snapshotDao,
                configService, traceModule.getDynamicAdviceCache(), parsedTypeCache,
                instrumentation, traceCollector, dataSource, traceRegistry);

        // for now only a single http worker thread to keep # of threads down
        final int numWorkerThreads = 1;
        int port = getHttpServerPort(properties);
        httpServer = buildHttpServer(port, numWorkerThreads, new VersionJsonService(version),
                aggregateJsonService, tracePointJsonService, traceSummaryJsonService,
                snapshotHttpService, traceExportHttpService, jvmJsonService, configJsonService,
                adhocPointcutConfigJsonService, adminJsonService);
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        if (httpServer != null) {
            httpServer.close();
        }
    }

    @OnlyUsedByTests
    public int getPort() {
        if (httpServer == null) {
            return -1;
        } else {
            return httpServer.getPort();
        }
    }

    @OnlyUsedByTests
    public TraceExportHttpService getTraceExportHttpService() {
        return traceExportHttpService;
    }

    private static int getHttpServerPort(@ReadOnly Map<String, String> properties) {
        String uiPort = properties.get("ui.port");
        if (uiPort == null) {
            return DEFAULT_UI_PORT;
        }
        try {
            return Integer.parseInt(uiPort);
        } catch (NumberFormatException e) {
            logger.warn("invalid -Dinformant.ui.port value '{}', proceeding with default value"
                    + " '{}'", uiPort, DEFAULT_UI_PORT);
            return DEFAULT_UI_PORT;
        }
    }

    @Nullable
    private static HttpServer buildHttpServer(int port, int numWorkerThreads,
            VersionJsonService versionJsonService, AggregateJsonService aggregateJsonService,
            TracePointJsonService tracePointJsonService,
            TraceSummaryJsonService traceSummaryJsonService,
            SnapshotHttpService snapshotHttpService, TraceExportHttpService traceExportHttpService,
            JvmJsonService jvmJsonService, ConfigJsonService configJsonService,
            AdhocPointcutConfigJsonService adhocPointcutConfigJsonService,
            AdminJsonService adminJsonService) {

        String resourceBase = "io/informant/local/ui/app-dist";

        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/search.html$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/config.html$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/pointcuts.html$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/jvm-options.html$"), resourceBase + "/index.html");
        uriMappings.put(Pattern.compile("^/thread-dump.html$"), resourceBase + "/index.html");
        // internal resources
        uriMappings.put(Pattern.compile("^/scripts/(.*)$"), resourceBase + "/scripts/$1");
        uriMappings.put(Pattern.compile("^/styles/(.*)$"), resourceBase + "/styles/$1");
        uriMappings.put(Pattern.compile("^/favicon.ico$"), resourceBase + "/favicon.ico");
        uriMappings.put(Pattern.compile("^/bower_components/(.*)$"),
                resourceBase + "/bower_components/$1");
        uriMappings.put(Pattern.compile("^/sources/(.*)$"), resourceBase + "/sources/$1");
        // services
        // export service is not bound under /backend since the export url is visible to users as
        // the download url for the export file
        uriMappings.put(Pattern.compile("^/export/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/detail/.*$"), snapshotHttpService);

        // the parentheses define the part of the match that is used to construct the args for
        // calling the method in json service, e.g. /backend/trace/summary/abc123 below calls the
        // method getSummary("abc123") in TraceSummaryJsonService
        ImmutableList.Builder<JsonServiceMapping> jsonServiceMappings = ImmutableList.builder();
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/version$",
                versionJsonService, "getVersion"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/aggregate/points$",
                aggregateJsonService, "getPoints"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/aggregate/groupings",
                aggregateJsonService, "getGroupings"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/trace/points$",
                tracePointJsonService, "getPoints"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/trace/summary/(.+)$",
                traceSummaryJsonService, "getSummary"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/supported",
                jvmJsonService, "getSupported"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/general",
                jvmJsonService, "getGeneralInfo"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/system-properties",
                jvmJsonService, "getSystemProperties"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/thread-dump$",
                jvmJsonService, "getThreadDump"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/heap-dump-defaults",
                jvmJsonService, "getHeapDumpDefaults"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/jvm/check-disk-space",
                jvmJsonService, "checkDiskSpace"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/jvm/dump-heap$",
                jvmJsonService, "dumpHeap"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/diagnostic-options",
                jvmJsonService, "getDiagnosticOptions"));
        jsonServiceMappings.add(new JsonServiceMapping(POST,
                "^/backend/jvm/update-diagnostic-options", jvmJsonService,
                "updateDiagnosticOptions"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/jvm/all-options",
                jvmJsonService, "getAllOptions"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config$",
                configJsonService, "getConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config/general$",
                configJsonService, "getGeneralConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/general$",
                configJsonService, "updateGeneralConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config/coarse-profiling$",
                configJsonService, "getCoarseProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/coarse-profiling$",
                configJsonService, "updateCoarseProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/config/fine-profiling-section$",
                configJsonService, "getFineProfilingSection"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/fine-profiling$",
                configJsonService, "updateFineProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config/user-overrides",
                configJsonService, "getUserOverridesConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/user-overrides",
                configJsonService, "updateUserOverridesConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config/storage-section",
                configJsonService, "getStorageSection"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/storage",
                configJsonService, "updateStorageConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/config/plugin-section$",
                configJsonService, "getPluginSection"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/plugin/(.+)$",
                configJsonService, "updatePluginConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/config/adhoc-pointcut-section$",
                configJsonService, "getAdhocPointcutSection"));
        jsonServiceMappings.add(new JsonServiceMapping(POST,
                "^/backend/config/adhoc-pointcut/\\+$",
                configJsonService, "addAdhocPointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(POST,
                "^/backend/config/adhoc-pointcut/([0-9a-f]+)$", configJsonService,
                "updateAdhocPointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/config/adhoc-pointcut/-$",
                configJsonService, "removeAdhocPointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/adhoc-pointcut/matching-type-names", adhocPointcutConfigJsonService,
                "getMatchingTypeNames"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/adhoc-pointcut/matching-method-names", adhocPointcutConfigJsonService,
                "getMatchingMethodNames"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/adhoc-pointcut/matching-methods",
                adhocPointcutConfigJsonService, "getMatchingMethods"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/admin/data/delete-all$",
                adminJsonService, "deleteAllData"));
        jsonServiceMappings.add(new JsonServiceMapping(POST,
                "^/backend/admin/adhoc-pointcuts/reweave",
                adminJsonService, "reweaveAdhocPointcuts"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/admin/data/compact$",
                adminJsonService, "compactData"));
        jsonServiceMappings.add(new JsonServiceMapping(POST, "^/backend/admin/config/reset-all$",
                adminJsonService, "resetAllConfig"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/admin/num-pending-complete-traces$",
                adminJsonService, "getNumPendingCompleteTraces"));
        jsonServiceMappings.add(new JsonServiceMapping(GET,
                "^/backend/admin/num-stored-snapshots$",
                adminJsonService, "getNumStoredSnapshots"));
        jsonServiceMappings.add(new JsonServiceMapping(GET, "^/backend/admin/num-active-traces",
                adminJsonService, "getNumActiveTraces"));
        try {
            return new HttpServer(port, numWorkerThreads, uriMappings.build(),
                    jsonServiceMappings.build());
        } catch (ChannelException e) {
            // don't rethrow, allow everything else to proceed normally, but informant ui will not
            // be available
            return null;
        }
    }
}
