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
package org.glowroot.local.ui;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import org.jboss.netty.channel.ChannelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;

class LazyHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(LazyHttpServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // default two http worker threads to keep # of threads down
    private static final int numWorkerThreads =
            Integer.getInteger("glowroot.internal.ui.workerThreads", 2);

    private final String bindAddress;
    private final int port;
    private final HttpSessionManager httpSessionManager;
    private final IndexHtmlHttpService indexHtmlHttpService;
    private final LayoutJsonService layoutJsonService;
    private final TraceDetailHttpService traceDetailHttpService;
    private final TraceExportHttpService traceExportHttpService;
    private final List<Object> jsonServices;

    private volatile boolean initialized;
    private volatile @Nullable HttpServer httpServer;

    LazyHttpServer(String bindAddress, int port, HttpSessionManager httpSessionManager,
            IndexHtmlHttpService indexHtmlHttpService, LayoutJsonService layoutJsonService,
            TraceDetailHttpService traceDetailHttpService,
            TraceExportHttpService traceExportHttpService, List<Object> jsonServices) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.httpSessionManager = httpSessionManager;
        this.indexHtmlHttpService = indexHtmlHttpService;
        this.layoutJsonService = layoutJsonService;
        this.traceDetailHttpService = traceDetailHttpService;
        this.traceExportHttpService = traceExportHttpService;
        this.jsonServices = jsonServices;
    }

    void init(final Instrumentation instrumentation, final ConfigJsonService configJsonService) {
        // cannot start netty in premain otherwise can crash JVM
        // see https://github.com/netty/netty/issues/3233
        // and https://bugs.openjdk.java.net/browse/JDK-8041920
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForMain(instrumentation);
                    initNonLazy(configJsonService);
                    int port = httpServer != null ? httpServer.getPort() : -1;
                    startupLogger.info("Glowroot listening at http://localhost:{}", port);
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        });
    }

    void initNonLazy(ConfigJsonService configJsonService) {
        HttpServer httpServer = build();
        initialized = true;
        if (httpServer != null) {
            this.httpServer = httpServer;
            configJsonService.setHttpServer(httpServer);
        }
    }

    @Nullable
    HttpServer get() throws InterruptedException {
        for (int i = 0; i < 5000; i++) {
            if (initialized) {
                return httpServer;
            }
            Thread.sleep(100);
        }
        logger.warn("timeout occurred waiting for http server to initialize");
        return null;
    }

    @Nullable
    HttpServer getNonLazy() {
        return httpServer;
    }

    // httpServer is only null if it could not even bind to port 0 (any available port)
    private @Nullable HttpServer build() {
        String resourceBase = "org/glowroot/local/ui/app-dist";
        ImmutableMap.Builder<Pattern, Object> uriMappings = ImmutableMap.builder();
        // pages
        uriMappings.put(Pattern.compile("^/$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/performance$"), indexHtmlHttpService);
        uriMappings.put(Pattern.compile("^/performance/flame-graph$"), indexHtmlHttpService);
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
        // export service is not bound under /backend since the export url is visible to users
        // as the download url for the export file
        uriMappings.put(Pattern.compile("^/export/trace/.*$"), traceExportHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/entries$"), traceDetailHttpService);
        uriMappings.put(Pattern.compile("^/backend/trace/profile$"), traceDetailHttpService);
        try {
            return new HttpServer(bindAddress, port, numWorkerThreads, layoutJsonService,
                    uriMappings.build(), httpSessionManager, jsonServices);
        } catch (ChannelException e) {
            // binding to the specified port failed and binding to port 0 (any port) failed
            logger.error("error binding to any port, the user interface will not be available", e);
            return null;
        }
    }

    private static void waitForMain(Instrumentation instrumentation) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            Thread.sleep(100);
            for (Class<?> clazz : instrumentation.getInitiatedClasses(null)) {
                if (clazz.getName().equals("sun.launcher.LauncherHelper")) {
                    return;
                }
            }
        }
        // something has gone wrong
        logger.error("sun.launcher.LauncherHelper was never loaded");
    }
}
