/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.central;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.V09AgentRollupDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcServer {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final DownstreamServiceImpl downstreamService;

    private final @Nullable Server httpServer;
    private final @Nullable Server httpsServer;

    GrpcServer(String bindAddress, @Nullable Integer httpPort, @Nullable Integer httpsPort,
            File centralDir, AgentConfigDao agentConfigDao, AgentDao agentDao,
            EnvironmentDao environmentDao, HeartbeatDao heartbeatDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, TraceDao traceDao, V09AgentRollupDao v09AgentRollupDao,
            CentralAlertingService centralAlertingService, ClusterManager clusterManager,
            Clock clock, String version) throws IOException {

        downstreamService = new DownstreamServiceImpl(agentDao, v09AgentRollupDao, clusterManager);

        CollectorServiceImpl collectorService = new CollectorServiceImpl(agentDao, agentConfigDao,
                environmentDao, heartbeatDao, aggregateDao, gaugeValueDao, traceDao,
                v09AgentRollupDao, centralAlertingService, clock, version);

        if (httpPort == null) {
            httpServer = null;
        } else {
            httpServer = startServer(bindAddress, httpPort, false, centralDir, downstreamService,
                    collectorService);
            if (httpsPort == null) {
                startupLogger.info("gRPC listening on {}:{}", bindAddress, httpPort);
            } else {
                startupLogger.info("gRPC listening on {}:{} (HTTP)", bindAddress, httpPort);
            }
        }
        if (httpsPort == null) {
            httpsServer = null;
        } else {
            httpsServer = startServer(bindAddress, httpsPort, true, centralDir, downstreamService,
                    collectorService);
            startupLogger.info("gRPC listening on {}:{} (HTTPS)", bindAddress, httpsPort);
        }
    }

    private static Server startServer(String bindAddress, int port, boolean https, File centralDir,
            DownstreamServiceImpl downstreamService, CollectorServiceImpl collectorService)
            throws IOException {
        NettyServerBuilder builder =
                NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port));
        if (https) {
            builder.useTransportSecurity(
                    getHttpsConfFile(centralDir, "grpc-cert.pem", "cert.pem", "certificate"),
                    getHttpsConfFile(centralDir, "grpc-key.pem", "key.pem", "private key"));
        }
        return builder.addService(collectorService.bindService())
                .addService(downstreamService.bindService())
                // need to override default max message size of 4mb until streaming is implemented
                // for DownstreamService.EntriesResponse and FullTraceResponse
                .maxMessageSize(64 * 1024 * 1024)
                .build()
                .start();
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    void close() throws InterruptedException {
        // immediately start sending "shutting-down" responses for new downstream requests
        // and wait for existing downstream requests to complete before proceeding
        downstreamService.stopSendingDownstreamRequests();

        // "shutting-down" responses will continue to be sent for new downstream requests until
        // ClusterManager is closed at the very end of CentralModule.shutdown(), which will give
        // time for agents to reconnect to a new central cluster node, and for the UI to retry
        // for a few seconds if it receives a "shutting-down" response

        // shutdown to prevent new grpc requests
        if (httpsServer != null) {
            shutdown(httpsServer);
        }
        if (httpServer != null) {
            shutdown(httpServer);
        }
    }

    private static File getHttpsConfFile(File confDir, String fileName, String sharedFileName,
            String display) throws FileNotFoundException {
        File confFile = new File(confDir, fileName);
        if (confFile.exists()) {
            return confFile;
        } else {
            File sharedConfFile = new File(confDir, sharedFileName);
            if (sharedConfFile.exists()) {
                return sharedConfFile;
            } else {
                throw new FileNotFoundException("HTTPS is enabled, but " + fileName + " (or "
                        + sharedFileName + " if using the same " + display + " for both grpc and"
                        + " ui) was not found under '" + confDir.getAbsolutePath() + "'");
            }
        }
    }

    private static void shutdown(Server server) throws InterruptedException {
        // TODO shutdownNow() has been needed to interrupt grpc threads since grpc-java 1.7.0
        server.shutdownNow();
        if (!server.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for grpc server to terminate");
        }
    }
}
