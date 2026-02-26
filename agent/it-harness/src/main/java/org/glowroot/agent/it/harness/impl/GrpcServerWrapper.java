/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Constants;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceImplBase;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueResponseMessage;
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

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcServerWrapper {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerWrapper.class);

    private final ExecutorService executor;
    private final Server server;

    private final DownstreamServiceImpl downstreamService;

    private volatile @MonotonicNonNull AgentConfig agentConfig;

    GrpcServerWrapper(TraceCollector collector, int port) throws IOException {
        executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-IT-Harness-GRPC-Executor-%d")
                        .build());
        downstreamService = new DownstreamServiceImpl();
        server = NettyServerBuilder.forPort(port)
                .executor(executor)
                .addService(new CollectorServiceImpl(collector).bindService())
                .addService(downstreamService.bindService())
                .maxInboundMessageSize(1024 * 1024 * 100)
                // aggressive keep alive is used by agent to detect silently dropped connections
                // (see org.glowroot.agent.central.CentralConnection)
                .permitKeepAliveTime(20, SECONDS)
                .build()
                .start();
    }

    AgentConfig getAgentConfig() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (agentConfig == null && stopwatch.elapsed(SECONDS) < 10) {
            MILLISECONDS.sleep(10);
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
            MILLISECONDS.sleep(10);
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
        MILLISECONDS.sleep(100);
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    private class CollectorServiceImpl extends CollectorServiceImplBase {

        private final TraceCollector collector;
        private final Map<String, String> fullTexts = Maps.newConcurrentMap();

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
                    // CANCELLED is normal during agent reconnection
                    if (Status.fromThrowable(t).getCode() != Status.Code.CANCELLED) {
                        logger.error(t.getMessage(), t);
                    }
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
                StreamObserver<GaugeValueResponseMessage> responseObserver) {
            responseObserver.onNext(GaugeValueResponseMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<TraceStreamMessage> collectTraceStream(
                final StreamObserver<EmptyMessage> responseObserver) {
            return new StreamObserver<TraceStreamMessage>() {

                private @MonotonicNonNull String traceId;
                private List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private List<Trace.Entry> entries = Lists.newArrayList();
                private List<Aggregate.Query> queries = Lists.newArrayList();
                private @MonotonicNonNull Profile mainThreadProfile;
                private @MonotonicNonNull Profile auxThreadProfile;
                private Trace. /*@MonotonicNonNull*/ Header header;

                @Override
                public void onNext(TraceStreamMessage value) {
                    try {
                        onNextInternal(value);
                    } catch (RuntimeException t) {
                        logger.error(t.getMessage(), t);
                        throw t;
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                        throw new RuntimeException(t);
                    }
                }

                @Override
                public void onCompleted() {
                    try {
                        onCompletedInternal(responseObserver);
                    } catch (RuntimeException t) {
                        logger.error(t.getMessage(), t);
                        throw t;
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                        throw new RuntimeException(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // CANCELLED is normal during agent reconnection
                    if (Status.fromThrowable(t).getCode() != Status.Code.CANCELLED) {
                        logger.error(t.getMessage(), t);
                    }
                }

                private void onNextInternal(TraceStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case STREAM_HEADER:
                            traceId = value.getStreamHeader().getTraceId();
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                                    .setFullText(resolveFullText(value.getSharedQueryText()))
                                    .build());
                            break;
                        case ENTRY:
                            entries.add(value.getEntry());
                            break;
                        case QUERIES:
                            queries.addAll(value.getQueries().getQueryList());
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

                private void onCompletedInternal(
                        final StreamObserver<EmptyMessage> responseObserver) {
                    Trace.Builder trace = Trace.newBuilder()
                            .setId(checkNotNull(traceId))
                            .setHeader(checkNotNull(header))
                            .addAllSharedQueryText(sharedQueryTexts)
                            .addAllEntry(entries)
                            .addAllQuery(queries);
                    if (mainThreadProfile != null) {
                        trace.setMainThreadProfile(mainThreadProfile);
                    }
                    if (auxThreadProfile != null) {
                        trace.setAuxThreadProfile(auxThreadProfile);
                    }
                    try {
                        collector.collectTrace(trace.build());
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
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
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private String resolveFullText(Trace.SharedQueryText sharedQueryText) {
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                String fullText = sharedQueryText.getFullText();
                if (fullText.length() > 2 * Constants.TRACE_QUERY_TEXT_TRUNCATE) {
                    fullTextSha1 = Hashing.sha1().hashString(fullText, UTF_8).toString();
                    fullTexts.put(fullTextSha1, fullText);
                }
                return fullText;
            }
            String fullText = fullTexts.get(fullTextSha1);
            if (fullText == null) {
                throw new IllegalStateException(
                        "Full text not found for sha1: " + fullTextSha1);
            }
            return fullText;
        }
    }

    private static class DownstreamServiceImpl extends DownstreamServiceImplBase {

        private final AtomicLong nextRequestId = new AtomicLong(1);

        // expiration in the unlikely case that response is never returned from agent
        private final Cache<Long, ResponseHolder> responseHolders = CacheBuilder.newBuilder()
                .expireAfterWrite(1, HOURS)
                .build();

        // use AtomicReference so that onError() for a stale connection cannot overwrite the
        // requestObserver that was set by a newer connection's connect() call
        private final AtomicReference<@Nullable StreamObserver<CentralRequest>> requestObserverRef =
                new AtomicReference<>();

        private volatile boolean closedByAgent;

        @Override
        public StreamObserver<AgentResponse> connect(
                final StreamObserver<CentralRequest> requestObserver) {
            requestObserverRef.set(requestObserver);
            return new StreamObserver<AgentResponse>() {
                @Override
                public void onNext(AgentResponse value) {
                    handleAgentResponse(value);
                }
                @Override
                public void onCompleted() {
                    requestObserver.onCompleted();
                    closedByAgent = true;
                }
                @Override
                public void onError(Throwable t) {
                    // use compareAndSet so that a stale connection's onError does not null out
                    // the requestObserver that was already replaced by a newer connection
                    requestObserverRef.compareAndSet(requestObserver, null);
                    // CANCELLED is normal during agent reconnection
                    if (Status.fromThrowable(t).getCode() != Status.Code.CANCELLED) {
                        logger.error(t.getMessage(), t);
                    }
                }
            };
        }

        private void handleAgentResponse(AgentResponse value) {
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
                // this shouldn't timeout since it is the other side of the exchange that is waiting
                responseHolder.response.exchange(value, 1, MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error(e.getMessage(), e);
            } catch (TimeoutException e) {
                logger.error(e.getMessage(), e);
            }
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
            Stopwatch stopwatch = Stopwatch.createStarted();
            while (true) {
                ResponseHolder responseHolder = new ResponseHolder();
                responseHolders.put(request.getRequestId(), responseHolder);
                StreamObserver<CentralRequest> observer;
                while ((observer = requestObserverRef.get()) == null) {
                    if (stopwatch.elapsed(MINUTES) >= 1) {
                        responseHolders.invalidate(request.getRequestId());
                        throw new TimeoutException();
                    }
                    MILLISECONDS.sleep(10);
                }
                observer.onNext(request);
                // poll for response, detecting connection changes so we can retry if needed
                while (true) {
                    try {
                        // timeout is in case agent never responds
                        // passing AgentResponse.getDefaultInstance() is just dummy (non-null) value
                        AgentResponse response = responseHolder.response
                                .exchange(AgentResponse.getDefaultInstance(), 1, SECONDS);
                        if (response.getMessageCase() == MessageCase.UNKNOWN_REQUEST_RESPONSE) {
                            throw new IllegalStateException();
                        }
                        if (response.getMessageCase() == MessageCase.EXCEPTION_RESPONSE) {
                            throw new IllegalStateException();
                        }
                        return response;
                    } catch (TimeoutException e) {
                        if (requestObserverRef.get() != observer) {
                            // the connection was re-established during the wait (e.g. the agent
                            // reconnected after a transient disconnect), so the request sent on
                            // the previous connection may have been lost; retry on new connection
                            responseHolders.invalidate(request.getRequestId());
                            request = request.toBuilder()
                                    .setRequestId(nextRequestId.getAndIncrement())
                                    .build();
                            break; // break inner loop to retry outer loop
                        }
                        if (stopwatch.elapsed(MINUTES) >= 1) {
                            responseHolders.invalidate(request.getRequestId());
                            throw e;
                        }
                    }
                }
            }
        }
    }

    private static class ResponseHolder {
        private final Exchanger<AgentResponse> response = new Exchanger<AgentResponse>();
    }
}
