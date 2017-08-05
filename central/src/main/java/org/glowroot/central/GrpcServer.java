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

import java.io.IOException;
import java.net.InetSocketAddress;

import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentRollupDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcServer {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final DownstreamServiceImpl downstreamService;

    private final ServerImpl server;

    GrpcServer(String bindAddress, int port, AgentRollupDao agentRollupDao,
            AgentConfigDao agentConfigDao, AggregateDao aggregateDao, GaugeValueDao gaugeValueDao,
            EnvironmentDao environmentDao, HeartbeatDao heartbeatDao, TraceDao traceDao,
            CentralAlertingService centralAlertingService, ClusterManager clusterManager,
            Clock clock, String version) throws IOException {

        downstreamService = new DownstreamServiceImpl(agentRollupDao, clusterManager);

        CollectorServiceImpl collectorService = new CollectorServiceImpl(agentRollupDao,
                agentConfigDao, environmentDao, aggregateDao, gaugeValueDao, heartbeatDao, traceDao,
                centralAlertingService, clock, version);

        server = NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
                .addService(collectorService.bindService())
                .addService(downstreamService.bindService())
                // need to override default max message size of 4mb until streaming is implemented
                // for DownstreamService.EntriesResponse and FullTraceResponse
                .maxMessageSize(64 * 1024 * 1024)
                .build()
                .start();

        startupLogger.info("gRPC listening on {}:{}", bindAddress, port);
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
        server.shutdown();
        // wait for existing grpc requests to complete
        if (!server.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for grpc server to terminate");
        }
    }
}
