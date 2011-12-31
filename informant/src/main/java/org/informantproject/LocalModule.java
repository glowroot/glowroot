/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject;

import java.util.HashMap;
import java.util.Map;

import org.informantproject.local.configuration.ReadConfigurationJsonService;
import org.informantproject.local.configuration.UpdateConfigurationJsonService;
import org.informantproject.local.metrics.LocalMetricRepository;
import org.informantproject.local.metrics.MetricJsonService;
import org.informantproject.local.trace.LocalTraceRepository;
import org.informantproject.local.trace.TraceJsonService;
import org.informantproject.local.ui.LocalHttpHandler;
import org.informantproject.local.ui.LocalHttpHandler.JsonService;
import org.informantproject.metric.MetricRepository;
import org.informantproject.trace.TraceRepository;
import org.informantproject.util.SimpleHttpServer;
import org.informantproject.util.SimpleHttpServer.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Guice module for local storage of traces and metrics. Some day there may be another module for
 * remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class LocalModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalModule.class);

    private final int httpServerPort;

    LocalModule(int httpServerPort) {
        this.httpServerPort = httpServerPort;
    }

    static void start(Injector injector) {
        logger.debug("start()");
        injector.getInstance(SimpleHttpServer.class);
    }

    static void shutdown(Injector injector) {
        logger.debug("shutdown()");
        injector.getInstance(SimpleHttpServer.class).shutdown();
    }

    @Override
    protected void configure() {
        logger.debug("configure()");
        // singleton: expensive to create
        bind(TraceRepository.class).to(LocalTraceRepository.class);
        bind(MetricRepository.class).to(LocalMetricRepository.class);
    }

    @Provides
    @Singleton
    protected SimpleHttpServer providesSimpleHttpServer(HttpHandler httpHandler) {
        logger.debug("providesHttpServer()");
        return new SimpleHttpServer(httpServerPort, httpHandler, "Informant-");
    }

    @Provides
    @Singleton
    protected static HttpHandler providesHttpHandler(Injector injector) {
        logger.debug("providesHttpHandler()");
        Map<String, JsonService> jsonServiceMap = new HashMap<String, JsonService>();
        jsonServiceMap.put("/traces", injector.getInstance(TraceJsonService.class));
        jsonServiceMap.put("/metrics", injector.getInstance(MetricJsonService.class));
        jsonServiceMap.put("/configuration/read",
                injector.getInstance(ReadConfigurationJsonService.class));
        jsonServiceMap.put("/configuration/update",
                injector.getInstance(UpdateConfigurationJsonService.class));
        return new LocalHttpHandler(jsonServiceMap);
    }
}
