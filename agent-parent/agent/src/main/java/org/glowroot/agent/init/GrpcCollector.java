/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.init;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceStub;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.JvmInfoMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceStub;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.wire.api.model.JvmInfoOuterClass.JvmInfo;
import org.glowroot.wire.api.model.LogEventOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcCollector implements Collector {

    static final Logger logger = LoggerFactory.getLogger(GrpcCollector.class);

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;
    private final CollectorServiceStub collectorServiceStub;
    private final DownstreamServiceStub downstreamServiceStub;

    private final String serverId;

    private final LoggingStreamObserver loggingStreamObserver = new LoggingStreamObserver();

    // limit error logging to once per minute
    private final RateLimiter loggingRateLimiter = RateLimiter.create(1 / 60.0);

    private final DownstreamServiceObserver downstreamServiceObserver;

    private volatile boolean closed;

    GrpcCollector(String serverId, String collectorHost, int collectorPort,
            ConfigUpdateService configUpdateService, LiveJvmService liveJvmService) {
        eventLoopGroup = EventLoopGroups.create("Glowroot-grpc-worker-ELG");
        executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-grpc-executor-%d")
                .build());
        channel = NettyChannelBuilder
                .forAddress(collectorHost, collectorPort)
                .eventLoopGroup(eventLoopGroup)
                .executor(executor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();
        collectorServiceStub = CollectorServiceGrpc.newStub(channel);
        downstreamServiceStub = DownstreamServiceGrpc.newStub(channel);
        downstreamServiceObserver =
                new DownstreamServiceObserver(configUpdateService, liveJvmService);
        // FIXME need to detect reconnect and re-issue connect/hello
        StreamObserver<ClientResponse> responseObserver =
                downstreamServiceStub.connect(downstreamServiceObserver);
        responseObserver.onNext(ClientResponse.newBuilder()
                .setHello(Hello.newBuilder()
                        .setServerId(serverId))
                .build());
        downstreamServiceObserver.setResponseObserver(responseObserver);
        this.serverId = serverId;
    }

    @Override
    public void collectJvmInfo(JvmInfo jvmInfo) throws Exception {
        // FIXME for this one only, need to re-try if failure
        JvmInfoMessage jvmInfoMessage = JvmInfoMessage.newBuilder()
                .setServerId(serverId)
                .setJvmInfo(jvmInfo)
                .build();
        collectorServiceStub.collectJvmInfo(jvmInfoMessage, loggingStreamObserver);
    }

    @Override
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception {
        AggregateMessage aggregateMessage = AggregateMessage.newBuilder()
                .setServerId(serverId)
                .setCaptureTime(captureTime)
                .addAllAggregatesByType(aggregatesByType)
                .build();
        collectorServiceStub.collectAggregates(aggregateMessage, loggingStreamObserver);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .setServerId(serverId)
                .addAllGaugeValues(gaugeValues)
                .build();
        collectorServiceStub.collectGaugeValues(gaugeValueMessage, loggingStreamObserver);
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        TraceMessage traceMessage = TraceMessage.newBuilder()
                .setServerId(serverId)
                .setTrace(trace)
                .build();
        collectorServiceStub.collectTrace(traceMessage, loggingStreamObserver);
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        if (closed) {
            return;
        }
        LogMessage logMessage = LogMessage.newBuilder()
                .setServerId(serverId)
                .setLogEvent(logEvent)
                .build();
        collectorServiceStub.log(logMessage, loggingStreamObserver);
    }

    @Override
    @OnlyUsedByTests
    public void close() throws InterruptedException {
        closed = true;
        downstreamServiceObserver.close();
        channel.shutdown();
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC channel");
        }
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC event loop group");
        }
    }

    private class LoggingStreamObserver implements StreamObserver<EmptyMessage> {

        @Override
        public void onNext(EmptyMessage value) {}

        @Override
        public void onError(Throwable t) {
            // limit error logging to once per minute
            if (loggingRateLimiter.tryAcquire()) {
                // this server error will not be sent back to the server (see GrpcLogbackAppender)
                logger.error(t.getMessage(), t);
            }
        }

        @Override
        public void onCompleted() {}
    }
}
