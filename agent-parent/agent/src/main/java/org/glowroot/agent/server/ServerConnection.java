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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.SECONDS;

class ServerConnection {

    private static final Logger logger = LoggerFactory.getLogger(ServerConnection.class);

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> suppressLogCollector = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;

    private final ScheduledExecutorService scheduledExecutor;

    private volatile boolean closed;

    ServerConnection(String collectorHost, int collectorPort,
            ScheduledExecutorService scheduledExecutor) {
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
        this.scheduledExecutor = scheduledExecutor;
    }

    boolean suppressLogCollector() {
        return suppressLogCollector.get();
    }

    ManagedChannel getChannel() {
        return channel;
    }

    // important that these calls are idempotent (at least in glowroot server implementation)
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        // TODO revisit retry/backoff after next grpc version

        // 60 seconds should be enough time to restart single glowroot server instance without
        // losing data (though better to use glowroot server cluster)
        //
        // this cannot retry over too long a period since it retains memory of rpc message for that
        // duration
        call.call(new RetryingStreamObserver<T>(call, 60, 60));
    }

    // important that these calls are idempotent (at least in glowroot server implementation)
    <T extends /*@NonNull*/ Object> void callUntilSuccessful(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        call.call(new RetryingStreamObserver<T>(call, 15, -1));
    }

    void suppressLogCollector(Runnable runnable) {
        boolean priorValue = suppressLogCollector.get();
        suppressLogCollector.set(true);
        try {
            runnable.run();
        } finally {
            suppressLogCollector.set(priorValue);
        }
    }

    @OnlyUsedByTests
    void close() {
        closed = true;
        channel.shutdown();
    }

    @OnlyUsedByTests
    void awaitClose() throws InterruptedException {
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

    static abstract class GrpcCall<T extends /*@NonNull*/ Object> {
        abstract void call(StreamObserver<T> responseObserver);
        void doWithResponse(@SuppressWarnings("unused") T response) {}
    }

    private class RetryingStreamObserver<T extends /*@NonNull*/ Object>
            implements StreamObserver<T> {

        private final GrpcCall<T> grpcCall;
        private final int maxSingleDelayInSeconds;
        private final int maxTotalInSeconds;
        private final Stopwatch stopwatch = Stopwatch.createStarted();

        private volatile long nextDelayInSeconds = 1;

        private RetryingStreamObserver(GrpcCall<T> grpcCall, int maxSingleDelayInSeconds,
                int maxTotalInSeconds) {
            this.grpcCall = grpcCall;
            this.maxTotalInSeconds = maxTotalInSeconds;
            this.maxSingleDelayInSeconds = maxSingleDelayInSeconds;
        }

        @Override
        public void onNext(T value) {
            grpcCall.doWithResponse(value);
        }

        @Override
        public void onError(final Throwable t) {
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.debug(t.getMessage(), t);
                }
            });
            if (maxTotalInSeconds != -1 && stopwatch.elapsed(SECONDS) > maxTotalInSeconds) {
                // no logging since DownstreamServiceObserver handles logging glowroot server
                // connectivity
                return;
            }
            // TODO revisit retry/backoff after next grpc version
            scheduledExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        grpcCall.call(RetryingStreamObserver.this);
                    } catch (final Throwable t) {
                        // intentionally capturing InterruptedException here as well to ensure
                        // reconnect is attempted no matter what
                        suppressLogCollector(new Runnable() {
                            @Override
                            public void run() {
                                logger.error(t.getMessage(), t);
                            }
                        });
                    }
                }
            }, nextDelayInSeconds, SECONDS);
            nextDelayInSeconds = Math.min(nextDelayInSeconds * 2, maxSingleDelayInSeconds);
        }

        @Override
        public void onCompleted() {}
    }
}
