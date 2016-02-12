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
package org.glowroot.agent.central;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralConnection.GrpcCall;
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

public class CentralCollectorImpl implements Collector {

    static final Logger logger = LoggerFactory.getLogger(CentralCollectorImpl.class);

    private final String serverId;
    private final CentralConnection centralConnection;
    private final CollectorServiceStub collectorServiceStub;
    private final DownstreamServiceObserver downstreamServiceObserver;

    public CentralCollectorImpl(Map<String, String> properties, @Nullable String collectorHost,
            LiveJvmService liveJvmService, LiveWeavingService liveWeavingService,
            LiveTraceRepository liveTraceRepository, ScheduledExecutorService scheduledExecutor,
            AgentConfigUpdater agentConfigUpdater) throws Exception {

        String serverId = properties.get("glowroot.server.id");
        if (Strings.isNullOrEmpty(serverId)) {
            serverId = InetAddress.getLocalHost().getHostName();
        }
        String collectorPortStr = properties.get("glowroot.collector.port");
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPortStr = System.getProperty("glowroot.collector.port");
        }
        int collectorPort;
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPort = 80;
        } else {
            collectorPort = Integer.parseInt(collectorPortStr);
        }
        checkNotNull(collectorHost);
        this.serverId = serverId;

        centralConnection = new CentralConnection(collectorHost, collectorPort, scheduledExecutor);
        collectorServiceStub = CollectorServiceGrpc.newStub(centralConnection.getChannel());
        downstreamServiceObserver = new DownstreamServiceObserver(centralConnection,
                agentConfigUpdater, liveJvmService, liveWeavingService, liveTraceRepository,
                serverId);
        downstreamServiceObserver.connectAsync();
    }

    @Override
    public void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
            final AgentConfigUpdater agentConfigUpdater) {
        final InitMessage initMessage = InitMessage.newBuilder()
                .setServerId(serverId)
                .setSystemInfo(systemInfo)
                .setAgentConfig(agentConfig)
                .build();
        centralConnection.callUntilSuccessful(new GrpcCall<InitResponse>() {
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
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType) {
        final AggregateMessage aggregateMessage = AggregateMessage.newBuilder()
                .setServerId(serverId)
                .setCaptureTime(captureTime)
                .addAllAggregatesByType(aggregatesByType)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectAggregates(aggregateMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) {
        final GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .setServerId(serverId)
                .addAllGaugeValues(gaugeValues)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectGaugeValues(gaugeValueMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectTrace(Trace trace) {
        final TraceMessage traceMessage = TraceMessage.newBuilder()
                .setServerId(serverId)
                .setTrace(trace)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectTrace(traceMessage, responseObserver);
            }
        });
    }

    @Override
    public void log(LogEvent logEvent) {
        if (centralConnection.suppressLogCollector()) {
            return;
        }
        final LogMessage logMessage = LogMessage.newBuilder()
                .setServerId(serverId)
                .setLogEvent(logEvent)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.log(logMessage, responseObserver);
            }
        });
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        downstreamServiceObserver.close();
        centralConnection.close();
    }

    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        centralConnection.awaitClose();
    }
}
