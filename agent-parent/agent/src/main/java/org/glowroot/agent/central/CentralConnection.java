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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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

class CentralConnection {

    private static final Logger logger = LoggerFactory.getLogger(CentralConnection.class);

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

    CentralConnection(String collectorHost, int collectorPort,
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

    // important that these calls are idempotent (at least in central implementation)
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(GrpcOneWayCall<T> call) {
        if (closed) {
            return;
        }
        // TODO revisit retry/backoff after next grpc version

        // 30 retries currently at 1 second apart covers 30 seconds which should be enough time to
        // restart single central instance without losing data (though better to use central
        // cluster)
        //
        // this cannot retry over too long a period since it retains memory of rpc message for that
        // duration
        call.call(new RetryingStreamObserver<T>(call, 30));
    }

    // important that these calls are idempotent (at least in central implementation)
    <T extends /*@NonNull*/ Object> void callUntilSuccessful(GrpcOneWayCall<T> call) {
        if (closed) {
            return;
        }
        call.call(new RetryingStreamObserver<T>(call, -1));
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

    interface GrpcOneWayCall<T extends /*@NonNull*/ Object> {
        void call(StreamObserver<T> responseObserver);
    }

    private class RetryingStreamObserver<T extends /*@NonNull*/ Object>
            implements StreamObserver<T> {

        private final GrpcOneWayCall<T> grpcOneWayCall;
        private final int maxRetries;

        private volatile int retryCounter;

        private RetryingStreamObserver(GrpcOneWayCall<T> grpcOneWayCall, int maxRetries) {
            this.grpcOneWayCall = grpcOneWayCall;
            this.maxRetries = maxRetries;
        }

        @Override
        public void onNext(T value) {}

        @Override
        public void onError(final Throwable t) {
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.debug(t.getMessage(), t);
                }
            });
            if (maxRetries != -1 && retryCounter++ > maxRetries) {
                // no logging since DownstreamServiceObserver handles logging central connectivity
                return;
            }
            // TODO revisit retry/backoff after next grpc version
            scheduledExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        grpcOneWayCall.call(RetryingStreamObserver.this);
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
            }, 1, SECONDS);
        }

        @Override
        public void onCompleted() {}
    }
}
