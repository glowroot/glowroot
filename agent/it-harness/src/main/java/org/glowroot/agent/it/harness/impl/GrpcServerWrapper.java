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
package org.glowroot.agent.it.harness.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceImplBase;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceImplBase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentResponse.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CentralRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcServerWrapper {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerWrapper.class);

    private final EventLoopGroup bossEventLoopGroup;
    private final EventLoopGroup workerEventLoopGroup;
    private final ExecutorService executor;
    private final Server server;

    private final DownstreamServiceImpl downstreamService;

    private volatile @MonotonicNonNull AgentConfig agentConfig;

    GrpcServerWrapper(TraceCollector collector, int port) throws IOException {
        bossEventLoopGroup = EventLoopGroups.create("Glowroot-IT-Harness-GRPC-Boss-ELG");
        workerEventLoopGroup = EventLoopGroups.create("Glowroot-IT-Harness-GRPC-Worker-ELG");
        executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-IT-Harness-GRPC-Executor-%d")
                        .build());
        downstreamService = new DownstreamServiceImpl();
        server = NettyServerBuilder.forPort(port)
                .bossEventLoopGroup(bossEventLoopGroup)
                .workerEventLoopGroup(workerEventLoopGroup)
                .executor(executor)
                .addService(new CollectorServiceImpl(collector).bindService())
                .addService(downstreamService.bindService())
                .maxMessageSize(1024 * 1024 * 100)
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

    void updateAgentConfig(AgentConfig agentConfig) throws Exception {
        downstreamService.updateAgentConfig(agentConfig);
        this.agentConfig = agentConfig;
    }

    int reweave() throws Exception {
        return downstreamService.reweave();
    }

    void close() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 10 && !downstreamService.closedByAgent) {
            Thread.sleep(10);
        }
        checkState(downstreamService.closedByAgent);
        // TODO shutdownNow() has been needed to interrupt grpc threads since grpc-java 1.7.0
        server.shutdownNow();
        if (!server.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate channel");
        }
        // not sure why, but server needs a little extra time to shut down properly
        // without this sleep, this warning is logged (but tests still pass):
        // io.grpc.netty.NettyServerHandler - Connection Error: RejectedExecutionException
        Thread.sleep(100);
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!bossEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate event loop group");
        }
        if (!workerEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate event loop group");
        }
    }

    private class CollectorServiceImpl extends CollectorServiceImplBase {

        private final TraceCollector collector;

        private CollectorServiceImpl(TraceCollector collector) {
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
        public StreamObserver<AggregateStreamMessage> collectAggregateStream(
                final StreamObserver<AggregateResponseMessage> responseObserver) {
            return new StreamObserver<AggregateStreamMessage>() {
                @Override
                public void onNext(AggregateStreamMessage value) {}
                @Override
                public void onError(Throwable t) {
                    logger.error(t.getMessage(), t);
                }
                @Override
                public void onCompleted() {
                    responseObserver.onNext(AggregateResponseMessage.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public void collectGaugeValues(GaugeValueMessage request,
                StreamObserver<EmptyMessage> responseObserver) {}

        @Override
        public StreamObserver<TraceStreamMessage> collectTraceStream(
                final StreamObserver<EmptyMessage> responseObserver) {
            return new StreamObserver<TraceStreamMessage>() {

                private List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private List<Trace.Entry> entries = Lists.newArrayList();
                private @MonotonicNonNull Profile mainThreadProfile;
                private @MonotonicNonNull Profile auxThreadProfile;
                private Trace. /*@MonotonicNonNull*/ Header header;

                @Override
                public void onNext(TraceStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case STREAM_HEADER:
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(value.getSharedQueryText());
                            break;
                        case ENTRY:
                            entries.add(value.getEntry());
                            break;
                        case MAIN_THREAD_PROFILE:
                            mainThreadProfile = value.getMainThreadProfile();
                            break;
                        case AUX_THREAD_PROFILE:
                            auxThreadProfile = value.getAuxThreadProfile();
                            break;
                        case HEADER:
                            header = value.getHeader();
                            break;
                        case STREAM_COUNTS:
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unexpected message: " + value.getMessageCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.error(t.getMessage(), t);
                }

                @Override
                public void onCompleted() {
                    checkNotNull(header);
                    Trace.Builder trace = Trace.newBuilder()
                            .setHeader(header)
                            .addAllSharedQueryText(sharedQueryTexts)
                            .addAllEntry(entries);
                    if (mainThreadProfile != null) {
                        trace.setMainThreadProfile(mainThreadProfile);
                    }
                    if (auxThreadProfile != null) {
                        trace.setAuxThreadProfile(auxThreadProfile);
                    }
                    try {
                        collector.collectTrace(trace.build());
                    } catch (Throwable t) {
                        responseObserver.onError(t);
                        return;
                    }
                    responseObserver.onNext(EmptyMessage.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            };
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

    private static class DownstreamServiceImpl extends DownstreamServiceImplBase {

        private final AtomicLong nextRequestId = new AtomicLong(1);

        // expiration in the unlikely case that response is never returned from agent
        private final Cache<Long, ResponseHolder> responseHolders = CacheBuilder.newBuilder()
                .expireAfterWrite(1, HOURS)
                .build();

        private final StreamObserver<AgentResponse> responseObserver =
                new StreamObserver<AgentResponse>() {
                    @Override
                    public void onNext(AgentResponse value) {
                        if (value.getMessageCase() == MessageCase.HELLO) {
                            return;
                        }
                        long requestId = value.getRequestId();
                        ResponseHolder responseHolder = responseHolders.getIfPresent(requestId);
                        responseHolders.invalidate(requestId);
                        if (responseHolder == null) {
                            logger.error("no response holder for request id: {}", requestId);
                            return;
                        }
                        try {
                            // this shouldn't timeout since it is the other side of the exchange
                            // that is waiting
                            responseHolder.response.exchange(value, 1, MINUTES);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.error(e.getMessage(), e);
                        } catch (TimeoutException e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                    @Override
                    public void onError(Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                    @Override
                    public void onCompleted() {
                        checkNotNull(requestObserver).onCompleted();
                        closedByAgent = true;
                    }
                };

        private volatile @MonotonicNonNull StreamObserver<CentralRequest> requestObserver;

        private volatile boolean closedByAgent;

        @Override
        public StreamObserver<AgentResponse> connect(
                StreamObserver<CentralRequest> requestObserver) {
            this.requestObserver = requestObserver;
            return responseObserver;
        }

        private void updateAgentConfig(AgentConfig agentConfig) throws Exception {
            sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                            .setAgentConfig(agentConfig))
                    .build());
        }

        private int reweave() throws Exception {
            AgentResponse response = sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                    .build());
            return response.getReweaveResponse().getClassUpdateCount();
        }

        private AgentResponse sendRequest(CentralRequest request) throws Exception {
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(request.getRequestId(), responseHolder);
            while (requestObserver == null) {
                Thread.sleep(10);
            }
            requestObserver.onNext(request);
            // timeout is in case agent never responds
            // passing AgentResponse.getDefaultInstance() is just dummy (non-null) value
            AgentResponse response = responseHolder.response
                    .exchange(AgentResponse.getDefaultInstance(), 1, MINUTES);
            if (response.getMessageCase() == MessageCase.UNKNOWN_REQUEST_RESPONSE) {
                throw new IllegalStateException();
            }
            if (response.getMessageCase() == MessageCase.EXCEPTION_RESPONSE) {
                throw new IllegalStateException();
            }
            return response;
        }
    }

    private static class ResponseHolder {
        private final Exchanger<AgentResponse> response = new Exchanger<AgentResponse>();
    }
}
