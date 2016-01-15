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
package org.glowroot.agent.it.harness.impl;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

public class GrpcServerWrapper {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerWrapper.class);

    private final EventLoopGroup bossEventLoopGroup;
    private final EventLoopGroup workerEventLoopGroup;
    private final ExecutorService executor;
    private final Server server;

    private final DownstreamServiceImpl downstreamService;

    private volatile @MonotonicNonNull AgentConfig agentConfig;

    public GrpcServerWrapper(Collector collector, int port) throws IOException {
        bossEventLoopGroup = EventLoopGroups.create("Glowroot-grpc-boss-ELG");
        workerEventLoopGroup = EventLoopGroups.create("Glowroot-grpc-worker-ELG");
        executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-grpc-executor-%d")
                        .build());
        downstreamService = new DownstreamServiceImpl();
        server = NettyServerBuilder.forPort(port)
                .bossEventLoopGroup(bossEventLoopGroup)
                .workerEventLoopGroup(workerEventLoopGroup)
                .executor(executor)
                .addService(CollectorServiceGrpc.bindService(new CollectorServiceImpl(collector)))
                .addService(DownstreamServiceGrpc.bindService(downstreamService))
                .build()
                .start();
    }

    AgentConfig getAgentConfig() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (agentConfig == null && stopwatch.elapsed(SECONDS) < 10) {
            Thread.sleep(10);
        }
        if (agentConfig == null) {
            throw new IllegalStateException("Timed out waiting to receive agent config");
        }
        return agentConfig;
    }

    void updateAgentConfig(AgentConfig agentConfig) throws InterruptedException {
        downstreamService.updateAgentConfig(agentConfig);
        this.agentConfig = agentConfig;
    }

    int reweave() throws InterruptedException {
        return downstreamService.reweave();
    }

    public void close() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 10 && !downstreamService.closedByAgent) {
            Thread.sleep(10);
        }
        checkState(downstreamService.closedByAgent);
        server.shutdown();
        if (!server.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC channel");
        }
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC executor");
        }
        if (!bossEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC boss event loop group");
        }
        if (!workerEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC worker event loop group");
        }
    }

    private class CollectorServiceImpl implements CollectorService {

        private final Collector collector;

        private CollectorServiceImpl(Collector collector) {
            this.collector = collector;
        }

        @Override
        public void collectInit(InitMessage request,
                StreamObserver<InitResponse> responseObserver) {
            agentConfig = request.getAgentConfig();
            responseObserver.onNext(InitResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectAggregates(AggregateMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                collector.collectAggregates(request.getCaptureTime(),
                        request.getAggregatesByTypeList());
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectGaugeValues(GaugeValueMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                collector.collectGaugeValues(request.getGaugeValuesList());
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectTrace(TraceMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                collector.collectTrace(request.getTrace());
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void log(LogMessage request, StreamObserver<EmptyMessage> responseObserver) {
            try {
                collector.log(request.getLogEvent());
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static class DownstreamServiceImpl implements DownstreamService {

        private final AtomicLong nextRequestId = new AtomicLong();

        private final ConcurrentMap<Long, ResponseHolder> responseHolders = Maps.newConcurrentMap();

        private final StreamObserver<ClientResponse> responseObserver =
                new StreamObserver<ClientResponse>() {
                    @Override
                    public void onNext(ClientResponse value) {
                        if (value.getMessageCase() == MessageCase.HELLO) {
                            return;
                        }
                        long requestId = value.getRequestId();
                        ResponseHolder responseHolder = responseHolders.get(requestId);
                        responseHolders.remove(requestId);
                        responseHolder.response = value;
                        synchronized (responseHolder) {
                            responseHolder.notifyAll();
                        }
                    }
                    @Override
                    public void onError(Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                    @Override
                    public void onCompleted() {
                        requestObserver.onCompleted();
                        closedByAgent = true;
                    }
                };

        private volatile @MonotonicNonNull StreamObserver<ServerRequest> requestObserver;

        private volatile boolean closedByAgent;

        private void updateAgentConfig(AgentConfig agentConfig) throws InterruptedException {
            while (requestObserver == null) {
                Thread.sleep(10);
            }
            long requestId = nextRequestId.getAndIncrement();
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(requestId, responseHolder);
            requestObserver.onNext(
                    ServerRequest.newBuilder()
                            .setRequestId(requestId)
                            .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                                    .setAgentConfig(agentConfig))
                            .build());
            synchronized (responseHolder) {
                while (responseHolder.response == null) {
                    responseHolder.wait();
                }
            }
        }

        private int reweave() throws InterruptedException {
            while (requestObserver == null) {
                Thread.sleep(10);
            }
            long requestId = nextRequestId.getAndIncrement();
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(requestId, responseHolder);
            requestObserver.onNext(
                    ServerRequest.newBuilder()
                            .setRequestId(requestId)
                            .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                            .build());
            synchronized (responseHolder) {
                while (responseHolder.response == null) {
                    responseHolder.wait();
                }
            }
            return responseHolder.response.getReweaveResponse().getClassUpdateCount();
        }

        @Override
        public StreamObserver<ClientResponse> connect(
                StreamObserver<ServerRequest> requestObserver) {
            this.requestObserver = requestObserver;
            return responseObserver;
        }
    }

    private static class ResponseHolder {
        private volatile ClientResponse response;
    }
}
