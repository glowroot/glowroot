/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.server;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.server.ServerConnection.GrpcCall;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceStub;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class ServerCollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(ServerCollectorImpl.class);

    private final String agentId;
    private final ServerConnection serverConnection;
    private final CollectorServiceStub collectorServiceStub;
    private final DownstreamServiceObserver downstreamServiceObserver;

    public ServerCollectorImpl(Map<String, String> properties, @Nullable String collectorHost,
            LiveJvmService liveJvmService, LiveWeavingService liveWeavingService,
            LiveTraceRepository liveTraceRepository, AgentConfigUpdater agentConfigUpdater)
            throws Exception {

        String agentId = properties.get("glowroot.agent.id");
        if (Strings.isNullOrEmpty(agentId)) {
            agentId = InetAddress.getLocalHost().getHostName();
        }
        String collectorPortStr = properties.get("glowroot.collector.port");
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPortStr = System.getProperty("glowroot.collector.port");
        }
        int collectorPort;
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPort = 8181;
        } else {
            collectorPort = Integer.parseInt(collectorPortStr);
        }
        checkNotNull(collectorHost);
        this.agentId = agentId;

        AtomicBoolean inConnectionFailure = new AtomicBoolean();
        serverConnection = new ServerConnection(collectorHost, collectorPort, inConnectionFailure);
        collectorServiceStub = CollectorServiceGrpc.newStub(serverConnection.getChannel())
                .withCompression("gzip");
        downstreamServiceObserver = new DownstreamServiceObserver(serverConnection,
                agentConfigUpdater, liveJvmService, liveWeavingService, liveTraceRepository,
                agentId, inConnectionFailure);
        downstreamServiceObserver.connectAsync();
    }

    @Override
    public void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
            final AgentConfigUpdater agentConfigUpdater) {
        final InitMessage initMessage = InitMessage.newBuilder()
                .setAgentId(agentId)
                .setSystemInfo(systemInfo)
                .setAgentConfig(agentConfig)
                .build();
        serverConnection.callUntilSuccessful(new GrpcCall<InitResponse>() {
            @Override
            public void call(StreamObserver<InitResponse> responseObserver) {
                collectorServiceStub.collectInit(initMessage, responseObserver);
            }
            @Override
            void doWithResponse(InitResponse response) {
                if (response.hasAgentConfig()) {
                    try {
                        agentConfigUpdater.update(response.getAgentConfig());
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        });
    }

    @Override
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType,
            List<String> sharedQueryTexts) {
        final AggregateMessage aggregateMessage = AggregateMessage.newBuilder()
                .setAgentId(agentId)
                .setCaptureTime(captureTime)
                .addAllAggregatesByType(aggregatesByType)
                .addAllSharedQueryText(sharedQueryTexts)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectAggregates(aggregateMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) {
        final GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .setAgentId(agentId)
                .addAllGaugeValues(gaugeValues)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectGaugeValues(gaugeValueMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectTrace(Trace trace) {
        final TraceMessage traceMessage = TraceMessage.newBuilder()
                .setAgentId(agentId)
                .setTrace(trace)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectTrace(traceMessage, responseObserver);
            }
        });
    }

    @Override
    public void log(LogEvent logEvent) {
        if (serverConnection.suppressLogCollector()) {
            return;
        }
        final LogMessage logMessage = LogMessage.newBuilder()
                .setAgentId(agentId)
                .setLogEvent(logEvent)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.log(logMessage, responseObserver);
            }
        });
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        downstreamServiceObserver.close();
        serverConnection.close();
    }

    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        serverConnection.awaitClose();
    }
}
