/*
 * Copyright 2014-2015 the original author or authors.
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

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LazyHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(LazyHttpServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final String bindAddress;
    private final int port;
    private final HttpSessionManager httpSessionManager;
    private final IndexHtmlHttpService indexHtmlHttpService;
    private final LayoutHttpService layoutHttpService;
    private final LayoutService layoutService;
    private final TraceDetailHttpService traceDetailHttpService;
    private final TraceExportHttpService traceExportHttpService;
    private final @Nullable GlowrootLogHttpService glowrootLogHttpService;
    private final List<Object> jsonServices;
    private final int numWorkerThreads;

    private volatile @Nullable HttpServer httpServer;

    LazyHttpServer(String bindAddress, int port, HttpSessionManager httpSessionManager,
            IndexHtmlHttpService indexHtmlHttpService, LayoutHttpService layoutHttpService,
            LayoutService layoutService, TraceDetailHttpService traceDetailHttpService,
            TraceExportHttpService traceExportHttpService,
            @Nullable GlowrootLogHttpService glowrootLogHttpService, List<Object> jsonServices,
            int numWorkerThreads) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.httpSessionManager = httpSessionManager;
        this.indexHtmlHttpService = indexHtmlHttpService;
        this.layoutHttpService = layoutHttpService;
        this.layoutService = layoutService;
        this.traceDetailHttpService = traceDetailHttpService;
        this.traceExportHttpService = traceExportHttpService;
        this.glowrootLogHttpService = glowrootLogHttpService;
        this.jsonServices = jsonServices;
        this.numWorkerThreads = numWorkerThreads;
    }

    void init(ConfigJsonService configJsonService) {
        HttpServer httpServer = build();
        if (httpServer != null) {
            this.httpServer = httpServer;
            configJsonService.setHttpServer(httpServer);
        }
        int port = httpServer != null ? httpServer.getPort() : -1;
        startupLogger.info("Glowroot listening at http://localhost:{}", port);
    }

    @Nullable
    HttpServer get() {
        return httpServer;
    }

    // httpServer is only null if it could not even bind to port 0 (any available port)
    private @Nullable HttpServer build() {
        Map<Pattern, HttpService> httpServices = Maps.newHashMap();
        // http services
        httpServices.put(Pattern.compile("^/$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/transaction/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/error/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/jvm/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/config/.*$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/login$"), indexHtmlHttpService);
        httpServices.put(Pattern.compile("^/backend/layout$"), layoutHttpService);
        // export service is not bound under /backend since the export url is visible to users
        // as the download url for the export file
        httpServices.put(Pattern.compile("^/export/trace$"), traceExportHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/queries$"), traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/entries$"), traceDetailHttpService);
        httpServices.put(Pattern.compile("^/backend/trace/profile$"), traceDetailHttpService);
        if (glowrootLogHttpService != null) {
            httpServices.put(Pattern.compile("^/backend/jvm/glowroot-log$"),
                    glowrootLogHttpService);
        }
        // services
        try {
            return new HttpServer(bindAddress, port, numWorkerThreads, layoutService, httpServices,
                    httpSessionManager, jsonServices);
        } catch (Exception e) {
            // binding to the specified port failed and binding to port 0 (any port) failed
            logger.error("error binding to any port, the user interface will not be available", e);
            return null;
        }
    }
}
