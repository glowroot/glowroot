/**
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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    private static final int DEFAULT_UI_PORT = 4000;

    private static final boolean devMode;

    static {
        devMode = Boolean.getBoolean("informant.internal.ui.devMode");
    }

    private final TraceExportHttpService traceExportHttpService;
    @Nullable
    private final HttpServer httpServer;

    public LocalUiModule(Ticker ticker, Clock clock, File dataDir, ConfigModule configModule,
            StorageModule storageModule, CollectorModule collectorModule, TraceModule traceModule,
            @Nullable Instrumentation instrumentation, @ReadOnly Map<String, String> properties)
            throws Exception {

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
        traceExportHttpService = new TraceExportHttpService(traceCommonService, devMode);
        // when port is 0, intentionally passing it as 0 instead of its resolved value since the
        // port is just displayed on config page for its documentation value anyways, and more
        // useful to know it was set to 0 than to display its value (which is needed to view the
        // page anyways)
        ConfigJsonService configJsonService = new ConfigJsonService(configService, rollingFile,
                pluginDescriptorCache, dataDir, traceModule.getAdviceCache(), instrumentation);
        PointcutConfigJsonService pointcutConfigJsonService = new PointcutConfigJsonService(
                parsedTypeCache);
        ThreadDumpJsonService threadDumpJsonService = new ThreadDumpJsonService();
        AdminJsonService adminJsonService = new AdminJsonService(snapshotDao,
                configService, traceModule.getAdviceCache(), parsedTypeCache, instrumentation,
                traceCollector, dataSource, traceRegistry);

        // for now only a single http worker thread to keep # of threads down
        final int numWorkerThreads = 1;
        int port = getHttpServerPort(properties);
        httpServer = buildHttpServer(port, numWorkerThreads, aggregateJsonService,
                tracePointJsonService, traceSummaryJsonService, snapshotHttpService,
                traceExportHttpService, configJsonService, pointcutConfigJsonService,
                threadDumpJsonService, adminJsonService, devMode);
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
            AggregateJsonService aggregateJsonService,
            TracePointJsonService tracePointJsonService,
            TraceSummaryJsonService traceSummaryJsonService,
            SnapshotHttpService snapshotHttpService,
            TraceExportHttpService traceExportHttpService, ConfigJsonService configJsonService,
            PointcutConfigJsonService pointcutConfigJsonService,
            ThreadDumpJsonService threadDumpJsonService, AdminJsonService adminJsonService,
            boolean devMode) {

        String resourceBase = devMode ? "io/informant/local/ui" : "io/informant/local/ui-build";

        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), resourceBase + "/home.html");
        uriMappings.put(Pattern.compile("^/search.html$"), resourceBase + "/search.html");
        uriMappings.put(Pattern.compile("^/config.html$"), resourceBase + "/config.html");
        uriMappings.put(Pattern.compile("^/pointcuts.html$"), resourceBase + "/pointcuts.html");
        uriMappings.put(Pattern.compile("^/threaddump.html$"), resourceBase + "/threaddump.html");
        // internal resources
        uriMappings.put(Pattern.compile("^/img/(.*)$"), resourceBase + "/img/$1");
        uriMappings.put(Pattern.compile("^/js/(.*)$"), resourceBase + "/js/$1");
        if (devMode) {
            uriMappings.put(Pattern.compile("^/less/(.*)$"), resourceBase + "/less/$1");
            uriMappings.put(Pattern.compile("^/template/(.*)$"), resourceBase + "/template/$1");
            uriMappings.put(Pattern.compile("^/lib/(.*)$"), resourceBase + "/lib/$1");
        } else {
            uriMappings.put(Pattern.compile("^/css/(.*)$"), resourceBase + "/css/$1");
            uriMappings.put(Pattern.compile("^/lib/requirejs/require.js$"),
                    resourceBase + "/lib/requirejs/require.js");
            uriMappings.put(Pattern.compile("^/lib/bootstrap/img/(.*)$"),
                    resourceBase + "/lib/bootstrap/img/$1");
            uriMappings.put(Pattern.compile("^/lib/specialelite/(.*)$"),
                    resourceBase + "/lib/specialelite/$1");
            uriMappings.put(Pattern.compile("^/lib/flashcanvas/flashcanvas.swf$"),
                    resourceBase + "/lib/flashcanvas/flashcanvas.swf");
            // TODO after upgrading to less 1.4.0, this css file can be bundled during the less
            // process, see http://stackoverflow.com/a/16830938/295416
            // (also see search.less and informant/pom.xml)
            uriMappings.put(Pattern.compile("^/lib/qtip/jquery.qtip.css"),
                    resourceBase + "/lib/qtip/jquery.qtip.css");
        }
        // services
        uriMappings.put(Pattern.compile("^/trace/export/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/trace/detail/.*$"), snapshotHttpService);

        // the parentheses define the part of the match that is used to construct the args for
        // calling the method in json service, e.g. /trace/summary/abc123 below calls the method
        // getSummary("abc123") in TraceSummaryJsonService
        ImmutableList.Builder<JsonServiceMapping> jsonServiceMappings = ImmutableList.builder();
        jsonServiceMappings.add(new JsonServiceMapping("^/aggregate/points$",
                aggregateJsonService, "getPoints"));
        jsonServiceMappings.add(new JsonServiceMapping("^/aggregate/groupings",
                aggregateJsonService, "getGroupings"));
        jsonServiceMappings.add(new JsonServiceMapping("^/trace/points$",
                tracePointJsonService, "getPoints"));
        jsonServiceMappings.add(new JsonServiceMapping("^/trace/summary/(.+)$",
                traceSummaryJsonService, "getSummary"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config$",
                configJsonService, "getConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/general$",
                configJsonService, "updateGeneralConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/coarse-profiling$",
                configJsonService, "updateCoarseProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/fine-profiling$",
                configJsonService, "updateFineProfilingConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/user",
                configJsonService, "updateUserConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/storage",
                configJsonService, "updateStorageConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/plugin/(.+)$",
                configJsonService, "updatePluginConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/pointcut/\\+$",
                configJsonService, "addPointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/pointcut/([0-9a-f]+)$",
                configJsonService, "updatePointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/config/pointcut/-$",
                configJsonService, "removePointcutConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/pointcut/matching-type-names",
                pointcutConfigJsonService, "getMatchingTypeNames"));
        jsonServiceMappings.add(new JsonServiceMapping("^/pointcut/matching-method-names",
                pointcutConfigJsonService, "getMatchingMethodNames"));
        jsonServiceMappings.add(new JsonServiceMapping("^/pointcut/matching-methods",
                pointcutConfigJsonService, "getMatchingMethods"));
        jsonServiceMappings.add(new JsonServiceMapping("^/threads/dump$",
                threadDumpJsonService, "getThreadDump"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/data/delete-all$",
                adminJsonService, "deleteAllData"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/pointcut/retransform-classes$",
                adminJsonService, "retransformClasses"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/data/compact$",
                adminJsonService, "compactData"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/config/reset-all$",
                adminJsonService, "resetAllConfig"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/num-pending-complete-traces$",
                adminJsonService, "getNumPendingCompleteTraces"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/num-stored-snapshots$",
                adminJsonService, "getNumStoredSnapshots"));
        jsonServiceMappings.add(new JsonServiceMapping("^/admin/num-active-traces",
                adminJsonService, "getNumActiveTraces"));
        try {
            return new HttpServer(port, numWorkerThreads, uriMappings.build(),
                    jsonServiceMappings.build(), devMode);
        } catch (ChannelException e) {
            // don't rethrow, allow everything else to proceed normally, but informant ui will not
            // be available
            return null;
        }
    }
}
