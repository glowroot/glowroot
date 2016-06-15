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

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.SECONDS;

class ServerConnection {

    private static final Logger logger = LoggerFactory.getLogger(ServerConnection.class);

    // back pressure on server connection
    private static final int PENDING_LIMIT = 100;

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> suppressLogCollector = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService channelExecutor;
    private final ManagedChannel channel;

    private final ScheduledExecutorService retryExecutor;

    private final AtomicBoolean inConnectionFailure;

    private final Random random = new Random();

    private final RateLimitedLogger backPressureLogger = new RateLimitedLogger();
    @GuardedBy("backPressureLogger")
    private int pendingRequestCount;

    private final RateLimitedLogger connectionErrorLogger = new RateLimitedLogger();

    private volatile boolean closed;

    ServerConnection(String collectorHost, int collectorPort, AtomicBoolean inConnectionFailure) {
        eventLoopGroup = EventLoopGroups.create("Glowroot-GRPC-Worker-ELG");
        channelExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-GRPC-Executor")
                .build());
        channel = NettyChannelBuilder
                .forAddress(collectorHost, collectorPort)
                .eventLoopGroup(eventLoopGroup)
                .executor(channelExecutor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();
        retryExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-Server-Retry")
                        .build());
        this.inConnectionFailure = inConnectionFailure;
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
        if (inConnectionFailure.get()) {
            return;
        }
        synchronized (backPressureLogger) {
            if (pendingRequestCount >= PENDING_LIMIT) {
                backPressureLogger.warn("not sending data to server because of an excessive backlog"
                        + " of {} requests in progress", PENDING_LIMIT);
                return;
            }
            pendingRequestCount++;
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
        // important here not to check inConnectionFailure, since need this to succeed if/when
        // connection is re-established
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
        retryExecutor.shutdown();
        channel.shutdown();
    }

    @OnlyUsedByTests
    void awaitClose() throws InterruptedException {
        if (!retryExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC channel");
        }
        channelExecutor.shutdown();
        if (!channelExecutor.awaitTermination(10, SECONDS)) {
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
            this.maxSingleDelayInSeconds = maxSingleDelayInSeconds;
            this.maxTotalInSeconds = maxTotalInSeconds;
        }

        @Override
        public void onNext(T value) {
            grpcCall.doWithResponse(value);
        }

        @Override
        public void onError(final Throwable t) {
            if (closed) {
                return;
            }
            if (inConnectionFailure.get()) {
                return;
            }
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.debug(t.getMessage(), t);
                }
            });
            if (maxTotalInSeconds != -1 && stopwatch.elapsed(SECONDS) > maxTotalInSeconds) {
                connectionErrorLogger.warn("error sending data to server: {}", t.getMessage(), t);
                synchronized (backPressureLogger) {
                    pendingRequestCount--;
                }
                return;
            }
            // TODO revisit retry/backoff after next grpc version
            retryExecutor.schedule(new Runnable() {
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
            // retry delay doubles on average each time, randomized +/- 50%
            double randomizedDoubling = 1.5 + random.nextDouble();
            nextDelayInSeconds = Math.min((long) (nextDelayInSeconds * randomizedDoubling),
                    maxSingleDelayInSeconds);
        }

        @Override
        public void onCompleted() {
            synchronized (backPressureLogger) {
                pendingRequestCount--;
            }
        }
    }
}
