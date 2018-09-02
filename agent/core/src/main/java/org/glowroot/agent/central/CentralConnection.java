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
package org.glowroot.agent.central;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.GuardedBy;
import javax.net.ssl.SSLException;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.RoundRobinLoadBalancerFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class CentralConnection {

    private static final Logger logger = LoggerFactory.getLogger(CentralConnection.class);

    // back pressure on connection to the central collector
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

    private final RateLimitedLogger discardingDataLogger =
            new RateLimitedLogger(CentralConnection.class);

    // count does not include init call
    @GuardedBy("discardingDataLogger")
    private int pendingRequestCount;

    private final RateLimitedLogger initConnectionErrorLogger =
            new RateLimitedLogger(CentralConnection.class, true);

    private final RateLimitedLogger connectionErrorLogger =
            new RateLimitedLogger(CentralConnection.class);

    private final String collectorAddress;

    private volatile boolean initCallSucceeded;
    private volatile boolean closed;

    CentralConnection(String collectorAddress, @Nullable String collectorAuthority,
            List<File> confDirs, AtomicBoolean inConnectionFailure) throws SSLException {
        ParsedCollectorAddress parsedCollectorAddress = parseCollectorAddress(collectorAddress);
        eventLoopGroup = EventLoopGroups.create("Glowroot-GRPC-Worker-ELG");
        channelExecutor =
                Executors.newSingleThreadExecutor(ThreadFactories.create("Glowroot-GRPC-Executor"));
        NettyChannelBuilder builder;
        if (parsedCollectorAddress.targets().size() == 1) {
            CollectorTarget target = parsedCollectorAddress.targets().get(0);
            builder = NettyChannelBuilder.forAddress(target.host(), target.port());
        } else {
            // this connection mechanism may be deprecated in the future in favor resolving a single
            // address to multiple collectors via DNS (above)
            String authority;
            if (collectorAuthority != null) {
                authority = collectorAuthority;
            } else if (!parsedCollectorAddress.https()) {
                authority = "dummy-service-authority";
            } else {
                throw new IllegalStateException("collector.authority is required when connecting"
                        + " over HTTPS to a comma-separated list of glowroot central collectors");
            }
            builder = NettyChannelBuilder.forTarget("dummy-target")
                    .nameResolverFactory(new MultipleAddressNameResolverFactory(
                            parsedCollectorAddress.targets(), authority));
        }
        // single address may resolve to multiple collectors above via DNS, so need to specify round
        // robin here even if only single address (first part of conditional above)
        builder.loadBalancerFactory(RoundRobinLoadBalancerFactory.getInstance())
                .eventLoopGroup(eventLoopGroup)
                .executor(channelExecutor)
                // aggressive keep alive, shouldn't even be used since gauge data is sent every
                // 5 seconds and keep alive will only kick in after 30 seconds of not hearing back
                // from the server
                .keepAliveTime(30, SECONDS);
        if (parsedCollectorAddress.https()) {
            SslContextBuilder sslContext = GrpcSslContexts.forClient();
            File trustCertCollectionFile = getTrustCertCollectionFile(confDirs);
            if (trustCertCollectionFile != null) {
                sslContext.trustManager(trustCertCollectionFile);
            }
            channel = builder.sslContext(sslContext.build())
                    .negotiationType(NegotiationType.TLS)
                    .build();
        } else {
            channel = builder.negotiationType(NegotiationType.PLAINTEXT)
                    .build();
        }
        retryExecutor = Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.create("Glowroot-Collector-Retry"));
        this.inConnectionFailure = inConnectionFailure;
        this.collectorAddress = collectorAddress;
    }

    boolean suppressLogCollector() {
        return suppressLogCollector.get();
    }

    ManagedChannel getChannel() {
        return channel;
    }

    <T extends /*@NonNull*/ Object> void callOnce(GrpcCall<T> call) {
        callWithAFewRetries(0, -1, call);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(GrpcCall<T> call) {
        callWithAFewRetries(0, call);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callWithAFewRetries(int initialDelayMillis,
            GrpcCall<T> call) {
        callWithAFewRetries(initialDelayMillis, 60, call);
    }

    // important that these calls are idempotent
    private <T extends /*@NonNull*/ Object> void callWithAFewRetries(int initialDelayMillis,
            final int maxTotalInSeconds, final GrpcCall<T> call) {
        if (closed) {
            return;
        }
        if (inConnectionFailure.get()) {
            return;
        }
        boolean logWarningAndDoNotSend = false;
        synchronized (discardingDataLogger) {
            if (pendingRequestCount >= PENDING_LIMIT) {
                logWarningAndDoNotSend = true;
            } else {
                pendingRequestCount++;
            }
        }
        if (logWarningAndDoNotSend) {
            // it is important not to perform logging under the above synchronized lock in order to
            // eliminate possibility of deadlock
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    discardingDataLogger.warn("not sending data to the central collector"
                            + " because pending request limit ({}) exceeded", PENDING_LIMIT);
                }
            });
            return;
        }
        // TODO revisit retry/backoff after next grpc version

        // 60 seconds should be enough time to restart central collector instance without losing
        // data (though better to use central collector cluster)
        //
        // this cannot retry over too long a period since it retains memory of rpc message for
        // that duration
        if (initialDelayMillis > 0) {
            retryExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        call.call(new RetryingStreamObserver<T>(call, maxTotalInSeconds,
                                maxTotalInSeconds, false));
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
            }, initialDelayMillis, MILLISECONDS);
        } else {
            call.call(new RetryingStreamObserver<T>(call, maxTotalInSeconds, maxTotalInSeconds,
                    false));
        }
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void callInit(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        // important here not to check inConnectionFailure, since need this to succeed if/when
        // connection is re-established
        call.call(new RetryingStreamObserver<T>(call, 15, -1, true));
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
            throw new IllegalStateException("Could not terminate channel");
        }
        channelExecutor.shutdown();
        if (!channelExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate event loop group");
        }
    }

    private static ParsedCollectorAddress parseCollectorAddress(String collectorAddress) {
        boolean https = false;
        List<CollectorTarget> targets = Lists.newArrayList();
        for (String addr : Splitter.on(',').trimResults().omitEmptyStrings()
                .split(collectorAddress)) {
            if (addr.startsWith("https://")) {
                if (!targets.isEmpty() && !https) {
                    throw new IllegalStateException("Cannot mix http and https addresses when using"
                            + " client side load balancing: " + collectorAddress);
                }
                addr = addr.substring("https://".length());
                https = true;
            } else {
                if (https) {
                    throw new IllegalStateException("Cannot mix http and https addresses when using"
                            + " client side load balancing: " + collectorAddress);
                }
                if (addr.startsWith("http://")) {
                    addr = addr.substring("http://".length());
                }
            }
            int index = addr.indexOf(':');
            if (index == -1) {
                throw new IllegalStateException(
                        "Invalid collector.address (missing port): " + addr);
            }
            String host = addr.substring(0, index);
            int port;
            try {
                port = Integer.parseInt(addr.substring(index + 1));
            } catch (NumberFormatException e) {
                logger.debug(e.getMessage(), e);
                throw new IllegalStateException(
                        "Invalid collector.address (invalid port): " + addr);
            }
            targets.add(ImmutableCollectorTarget.builder()
                    .host(host)
                    .port(port)
                    .build());
        }
        return ImmutableParsedCollectorAddress.builder()
                .https(https)
                .addAllTargets(targets)
                .build();
    }

    private static @Nullable File getTrustCertCollectionFile(List<File> confDirs) {
        for (File confDir : confDirs) {
            File confFile = new File(confDir, "grpc-trusted-root-certs.pem");
            if (confFile.exists()) {
                return confFile;
            }
        }
        return null;
    }

    private static String getRootCauseMessage(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null) {
            // using toString() instead of getMessage() in order to capture exception class name
            return t.toString();
        } else {
            return getRootCauseMessage(cause);
        }
    }

    @Value.Immutable
    interface ParsedCollectorAddress {
        boolean https();
        List<CollectorTarget> targets();
    }

    @Value.Immutable
    interface CollectorTarget {
        String host();
        int port();
    }

    abstract static class GrpcCall<T extends /*@NonNull*/ Object> {
        abstract void call(StreamObserver<T> responseObserver);
        void doWithResponse(@SuppressWarnings("unused") T response) {}
    }

    private class RetryingStreamObserver<T extends /*@NonNull*/ Object>
            implements StreamObserver<T> {

        private final GrpcCall<T> grpcCall;
        private final int maxSingleDelayInSeconds;
        private final int maxTotalInSeconds;
        private final boolean init;
        private final Stopwatch stopwatch = Stopwatch.createStarted();

        private volatile long nextDelayInSeconds = 4;

        private RetryingStreamObserver(GrpcCall<T> grpcCall, int maxSingleDelayInSeconds,
                int maxTotalInSeconds, boolean init) {
            this.grpcCall = grpcCall;
            this.maxSingleDelayInSeconds = maxSingleDelayInSeconds;
            this.maxTotalInSeconds = maxTotalInSeconds;
            this.init = init;
        }

        @Override
        public void onNext(T value) {
            try {
                grpcCall.doWithResponse(value);
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
            if (init) {
                initCallSucceeded = true;
            }
            decrementPendingRequestCount();
        }

        @Override
        public void onError(Throwable t) {
            try {
                onErrorInternal(t);
            } catch (RuntimeException u) {
                logger.error(u.getMessage(), u);
                throw u;
            } catch (Throwable u) {
                logger.error(u.getMessage(), u);
                throw new RuntimeException(u);
            }
        }

        private void onErrorInternal(final Throwable t) {
            if (closed) {
                decrementPendingRequestCount();
                return;
            }
            if (init) {
                suppressLogCollector(new Runnable() {
                    @Override
                    public void run() {
                        initConnectionErrorLogger.warn("unable to establish connection with the"
                                + " central collector {} (will keep trying...): {}",
                                collectorAddress, getRootCauseMessage(t));
                        logger.debug(t.getMessage(), t);
                    }
                });
            }
            if (inConnectionFailure.get()) {
                decrementPendingRequestCount();
                return;
            }
            suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.debug(t.getMessage(), t);
                }
            });
            if (!init && stopwatch.elapsed(SECONDS) > maxTotalInSeconds) {
                if (initCallSucceeded) {
                    suppressLogCollector(new Runnable() {
                        @Override
                        public void run() {
                            connectionErrorLogger.warn(
                                    "unable to send data to the central collector: {}",
                                    getRootCauseMessage(t));
                            logger.debug(t.getMessage(), t);
                        }
                    });
                }
                decrementPendingRequestCount();
                return;
            }

            // retry delay doubles on average each time, randomized +/- 50%
            double randomizedDoubling = 0.5 + random.nextDouble();
            long currDelay = (long) (nextDelayInSeconds * randomizedDoubling);
            nextDelayInSeconds = Math.min(nextDelayInSeconds * 2, maxSingleDelayInSeconds);

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
            }, currDelay, SECONDS);
        }

        private void decrementPendingRequestCount() {
            if (!init) {
                synchronized (discardingDataLogger) {
                    pendingRequestCount--;
                }
            }
        }
    }
}
