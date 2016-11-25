/*
 * Copyright 2014-2016 the original author or authors.
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

import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.ui.HttpServer.SocketBindException;

class LazyHttpServer {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final String bindAddress;
    private final int port;
    private final HttpSessionManager httpSessionManager;
    private final IndexHtmlHttpService indexHtmlHttpService;
    private final LayoutHttpService layoutHttpService;
    private final LayoutService layoutService;
    private final ConfigRepository configRepository;
    private final TraceDetailHttpService traceDetailHttpService;
    private final TraceExportHttpService traceExportHttpService;
    private final GlowrootLogHttpService glowrootLogHttpService;
    private final List<Object> jsonServices;
    private final File baseDir;
    private final Clock clock;
    private final int numWorkerThreads;

    private volatile @Nullable HttpServer httpServer;

    LazyHttpServer(String bindAddress, int port, HttpSessionManager httpSessionManager,
            IndexHtmlHttpService indexHtmlHttpService, LayoutHttpService layoutHttpService,
            LayoutService layoutService, ConfigRepository configRepository,
            TraceDetailHttpService traceDetailHttpService,
            TraceExportHttpService traceExportHttpService,
            GlowrootLogHttpService glowrootLogHttpService, List<Object> jsonServices,
            File baseDir, Clock clock, int numWorkerThreads) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.httpSessionManager = httpSessionManager;
        this.indexHtmlHttpService = indexHtmlHttpService;
        this.layoutHttpService = layoutHttpService;
        this.layoutService = layoutService;
        this.configRepository = configRepository;
        this.traceDetailHttpService = traceDetailHttpService;
        this.traceExportHttpService = traceExportHttpService;
        this.glowrootLogHttpService = glowrootLogHttpService;
        this.jsonServices = jsonServices;
        this.baseDir = baseDir;
        this.clock = clock;
        this.numWorkerThreads = numWorkerThreads;
    }

    void init(AdminJsonService adminJsonService) throws Exception {
        HttpServer httpServer;
        try {
            httpServer = build();
        } catch (SocketBindException e) {
            startupLogger.error(
                    "Error binding socket to {}:{}, the user interface will not be available",
                    bindAddress, port, e.getCause());
            return;
        } catch (Exception e) {
            startupLogger.error(
                    "Error starting the user interface, the user interface will not be available",
                    e.getCause());
            return;
        }
        this.httpServer = httpServer;
        adminJsonService.setHttpServer(httpServer);
        String bindAddress = httpServer.getBindAddress();
        int port = httpServer.getPort();
        if (bindAddress.equals("127.0.0.1")) {
            startupLogger.info("UI listening on {}:{} (to access the UI from remote machines,"
                    + " change the bind address to 0.0.0.0, either in the Glowroot UI under"
                    + " Configuration > Web, or directly in the admin.json file which then requires"
                    + " restart to take effect)", bindAddress, port);
        } else {
            startupLogger.info("UI listening on {}:{}", bindAddress, port);
        }
    }

    @Nullable
    HttpServer get() {
        return httpServer;
    }

    // httpServer is only null if it could not even bind to port 0 (any available port)
    private HttpServer build() throws Exception {
        Map<Pattern, HttpService> httpServices = Maps.newHashMap();
        // http services
        httpServices.put(Pattern.compile("^/$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/transaction/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/error/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/jvm/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/config/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/admin/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/change-password$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/login$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/backend/layout$"), layoutHttpService);
        // export service is not bound under /backend since the export url is visible to users
        // as the download url for the export file
        httpServices.put(Pattern.compile("^/export/trace$"), traceExportHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/entries$"), traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/main-thread-profile$"),
                traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/aux-thread-profile$"),
                traceDetailHttpService);
        httpServices.put(Pattern.compile("^/log$"), glowrootLogHttpService);
        return new HttpServer(bindAddress, port, numWorkerThreads, layoutService, configRepository,
                httpServices, httpSessionManager, jsonServices, baseDir, clock);
    }
}
