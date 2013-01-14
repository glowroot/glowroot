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
package io.informant.core;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.log.LogMessageSink;
import io.informant.core.trace.TraceSink;
import io.informant.local.log.LogMessageSinkLocal;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshotReaper;
import io.informant.local.ui.HttpServer;

import javax.annotation.concurrent.ThreadSafe;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Guice module for local storage of traces and metrics. Some day there may be another module for
 * remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class LocalModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(LocalModule.class);

    private static final int DEFAULT_UI_PORT = 4000;

    private final ImmutableMap<String, String> properties;

    LocalModule(ImmutableMap<String, String> properties) {
        this.properties = properties;
    }

    @Override
    protected void configure() {
        logger.debug("configure()");
        bind(TraceSink.class).to(TraceSinkLocal.class);
        bind(LogMessageSink.class).to(LogMessageSinkLocal.class);
    }

    @Provides
    @Singleton
    @Named("ui.port")
    int providesHttpServerPort() {
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

    static void start(Injector injector) {
        logger.debug("start()");
        injector.getInstance(HttpServer.class);
        injector.getInstance(TraceSnapshotReaper.class);
    }

    static void close(Injector injector) {
        logger.debug("close()");
        injector.getInstance(HttpServer.class).close();
        injector.getInstance(TraceSnapshotReaper.class).close();
    }
}
