/**
 * Copyright 2011-2012 the original author or authors.
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

import org.informantproject.local.metrics.MetricSinkLocal;
import org.informantproject.local.trace.TraceSinkLocal;
import org.informantproject.local.ui.HttpServer;
import org.informantproject.local.ui.HttpServer.LocalHttpServerPort;
import org.informantproject.metric.MetricSink;
import org.informantproject.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;

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

    @Override
    protected void configure() {
        logger.debug("configure()");
        bind(TraceSink.class).to(TraceSinkLocal.class);
        bind(MetricSink.class).to(MetricSinkLocal.class);
        bindConstant().annotatedWith(LocalHttpServerPort.class).to(httpServerPort);
    }

    static void start(Injector injector) {
        logger.debug("start()");
        injector.getInstance(HttpServer.class);
    }

    static void shutdown(Injector injector) {
        logger.debug("shutdown()");
        injector.getInstance(HttpServer.class).shutdown();
    }
}
