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

import java.util.List;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralConnection.GrpcOneWayCall;
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
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class CentralCollectorImpl implements Collector {

    static final Logger logger = LoggerFactory.getLogger(CentralCollectorImpl.class);

    private final CentralConnection centralConnection;
    private final CollectorServiceStub collectorServiceStub;
    private final String serverId;

    CentralCollectorImpl(CentralConnection centralConnection, String serverId) {
        this.centralConnection = centralConnection;
        collectorServiceStub = CollectorServiceGrpc.newStub(centralConnection.getChannel());
        this.serverId = serverId;
    }

    @Override
    public void collectInit(ProcessInfo jvmInfo, AgentConfig agentConfig) {
        final InitMessage initMessage = InitMessage.newBuilder()
                .setServerId(serverId)
                .setProcessInfo(jvmInfo)
                .setAgentConfig(agentConfig)
                .build();
        centralConnection.callUntilSuccessful(new GrpcOneWayCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectInit(initMessage, responseObserver);
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
        centralConnection.callWithAFewRetries(new GrpcOneWayCall<EmptyMessage>() {
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
        centralConnection.callWithAFewRetries(new GrpcOneWayCall<EmptyMessage>() {
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
        centralConnection.callWithAFewRetries(new GrpcOneWayCall<EmptyMessage>() {
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
        centralConnection.callWithAFewRetries(new GrpcOneWayCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.log(logMessage, responseObserver);
            }
        });
    }
}
