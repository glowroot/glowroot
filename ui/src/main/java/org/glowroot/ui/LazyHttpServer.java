/*
 * Copyright 2014-2017 the original author or authors.
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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.ui.HttpServer.SocketBindException;

class LazyHttpServer {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final String bindAddress;
    private final int port;
    private final ConfigRepository configRepository;
    private final CommonHandler commonHandler;
    private final File baseDir;
    private final int numWorkerThreads;

    private volatile @Nullable HttpServer httpServer;

    LazyHttpServer(String bindAddress, int port, ConfigRepository configRepository,
            CommonHandler commonHandler, File baseDir, int numWorkerThreads) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.configRepository = configRepository;
        this.commonHandler = commonHandler;
        this.baseDir = baseDir;
        this.numWorkerThreads = numWorkerThreads;
    }

    void init(AdminJsonService adminJsonService) throws Exception {
        HttpServer httpServer;
        try {
            httpServer = build();
        } catch (SocketBindException e) {
            startupLogger.error("Error binding socket to {}:{}, the user interface will not be"
                    + " available", bindAddress, port, e);
            return;
        } catch (Exception e) {
            startupLogger.error("Error starting the user interface, the user interface will not be"
                    + " available", e);
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
        return new HttpServer(bindAddress, port, numWorkerThreads, configRepository, commonHandler,
                baseDir);
    }
}
