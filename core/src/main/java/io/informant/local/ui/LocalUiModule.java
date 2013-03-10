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

import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.config.PluginDescriptorCache;
import io.informant.core.CoreModule;
import io.informant.core.TraceRegistry;
import io.informant.core.TraceSink;
import io.informant.local.store.DataSource;
import io.informant.local.store.DataSourceModule;
import io.informant.local.store.RollingFile;
import io.informant.local.store.SnapshotDao;
import io.informant.local.store.StorageModule;
import io.informant.util.Clock;
import io.informant.util.ThreadSafe;
import io.informant.weaving.ParsedTypeCache;

import java.io.File;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalUiModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalUiModule.class);

    private static final int DEFAULT_UI_PORT = 4000;

    private final TraceExportHttpService traceExportHttpService;
    private final HttpServer httpServer;

    public LocalUiModule(ConfigModule configModule, DataSourceModule dataSourceModule,
            StorageModule traceSinkModule, CoreModule coreModule,
            @ReadOnly Map<String, String> properties) throws Exception {

        Clock clock = configModule.getClock();
        Ticker ticker = configModule.getTicker();
        File dataDir = configModule.getDataDir();
        ConfigService configService = configModule.getConfigService();
        PluginDescriptorCache pluginDescriptorCache = configModule.getPluginDescriptorCache();

        DataSource dataSource = dataSourceModule.getDataSource();
        RollingFile rollingFile = traceSinkModule.getRollingFile();
        SnapshotDao snapshotDao = traceSinkModule.getSnapshotDao();
        TraceSink traceSink = coreModule.getTraceSink();
        ParsedTypeCache parsedTypeCache = coreModule.getParsedTypeCache();

        int port = getHttpServerPort(properties);
        TraceRegistry traceRegistry = coreModule.getTraceRegistry();

        TraceCommonService traceCommonService = new TraceCommonService(snapshotDao, traceRegistry,
                ticker);
        TracePointJsonService tracePointJsonService = new TracePointJsonService(snapshotDao,
                traceRegistry, traceSink, ticker, clock);
        TraceSummaryJsonService traceSummaryJsonService = new TraceSummaryJsonService(
                traceCommonService);
        SnapshotHttpService snapshotHttpService = new SnapshotHttpService(traceCommonService);
        traceExportHttpService = new TraceExportHttpService(traceCommonService);
        // when port is 0, intentionally passing it as 0 instead of its resolved value since the
        // port is just displayed on config page for its documentation value anyways, and more
        // useful to know it was set to 0 than to display its value (which is needed to view the
        // page anyways)
        ConfigJsonService configJsonService = new ConfigJsonService(configService, rollingFile,
                pluginDescriptorCache, dataDir, port);
        PointcutConfigJsonService pointcutConfigJsonService = new PointcutConfigJsonService(
                parsedTypeCache);
        ThreadDumpJsonService threadDumpJsonService = new ThreadDumpJsonService();
        AdminJsonService adminJsonService = new AdminJsonService(snapshotDao, configService,
                traceSink, dataSource, traceRegistry);

        // for now only a single http worker thread to keep # of threads down
        final int numWorkerThreads = 1;
        httpServer = new HttpServer(port, numWorkerThreads, tracePointJsonService,
                traceSummaryJsonService, snapshotHttpService, traceExportHttpService,
                configJsonService, pointcutConfigJsonService, threadDumpJsonService,
                adminJsonService);
    }

    public void close() {
        logger.debug("close()");
        httpServer.close();
    }

    public HttpServer getHttpServer() {
        return httpServer;
    }

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
}
