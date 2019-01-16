/*
 * Copyright 2015-2019 the original author or authors.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import org.glowroot.common.util.Throwables;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
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
    private final ExecutorService channelExecutor;
    private final ManagedChannel channel;

    private final ScheduledExecutorService retryExecutor;

    private final AtomicBoolean inConnectionFailure;

    private final Random random = new Random();

    private final RateLimitedLogger connectionErrorLogger =
            new RateLimitedLogger(CentralConnection.class);

    private final String collectorAddress;

    private volatile boolean inMaybeInitFailure;
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
            if (collectorAuthority != null) {
                builder.overrideAuthority(collectorAuthority);
            }
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

    <T extends /*@NonNull*/ Object> void blockingCallOnce(GrpcCall<T> call)
            throws InterruptedException {
        blockingCallWithAFewRetries(-1, call);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void blockingCallWithAFewRetries(GrpcCall<T> call)
            throws InterruptedException {
        blockingCallWithAFewRetries(30000, call);
    }

    // important that these calls are idempotent
    private <T extends /*@NonNull*/ Object> void blockingCallWithAFewRetries(int maxTotalMillis,
            GrpcCall<T> call) throws InterruptedException {
        if (closed) {
            return;
        }
        if (inConnectionFailure.get()) {
            return;
        }
        RetryingStreamObserver<T> responseObserver =
                new RetryingStreamObserver<T>(call, maxTotalMillis, maxTotalMillis, false);
        call.call(responseObserver);
        responseObserver.waitForFinish();
    }

    <T extends /*@NonNull*/ Object> void asyncCallOnce(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        if (inConnectionFailure.get()) {
            return;
        }
        RetryingStreamObserver<T> responseObserver =
                new RetryingStreamObserver<T>(call, -1, -1, false);
        call.call(responseObserver);
    }

    // important that these calls are idempotent
    <T extends /*@NonNull*/ Object> void asyncCallInit(GrpcCall<T> call) {
        if (closed) {
            return;
        }
        // important here not to check inConnectionFailure, since need this to succeed if/when
        // connection is re-established
        call.call(new RetryingStreamObserver<T>(call, 15000, -1, true));
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

    class RetryingStreamObserver<T extends /*@NonNull*/ Object> implements StreamObserver<T> {

        private final GrpcCall<T> grpcCall;
        private final int maxSingleDelayMillis;
        private final int maxTotalMillis;
        private final boolean init;
        private final Stopwatch stopwatch = Stopwatch.createStarted();

        private volatile long nextDelayMillis = 2000;

        private final CountDownLatch latch = new CountDownLatch(1);

        private RetryingStreamObserver(GrpcCall<T> grpcCall, int maxSingleDelayMillis,
                int maxTotalMillis, boolean init) {
            this.grpcCall = grpcCall;
            this.maxSingleDelayMillis = maxSingleDelayMillis;
            this.maxTotalMillis = maxTotalMillis;
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
                inMaybeInitFailure = false;
                initCallSucceeded = true;
            }
            latch.countDown();
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

        private void waitForFinish() throws InterruptedException {
            latch.await();
        }

        private void onErrorInternal(final Throwable t) throws InterruptedException {
            if (closed) {
                latch.countDown();
                return;
            }
            if (init && !inMaybeInitFailure) {
                // one free pass
                // try immediate re-connect once in case this is just node of central collector
                // cluster going down
                inMaybeInitFailure = true;
                grpcCall.call(RetryingStreamObserver.this);
                return;
            }
            if (init && !inConnectionFailure.getAndSet(true)) {
                // log first time only
                suppressLogCollector(new Runnable() {
                    @Override
                    public void run() {
                        logger.warn("unable to establish connection with the central collector {}"
                                + " (will keep trying...): {}", collectorAddress,
                                Throwables.getBestMessage(t));
                        logger.debug(t.getMessage(), t);
                    }
                });
            }
            if (!init && inConnectionFailure.get()) {
                latch.countDown();
                return;
            }
            if (logger.isDebugEnabled()) {
                suppressLogCollector(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug(t.getMessage(), t);
                    }
                });
            }
            if (!retryOnError(t)) {
                if (initCallSucceeded) {
                    suppressLogCollector(new Runnable() {
                        @Override
                        public void run() {
                            connectionErrorLogger.warn(
                                    "unable to send data to the central collector: {}",
                                    Throwables.getBestMessage(t));
                            logger.debug(t.getMessage(), t);
                        }
                    });
                }
                latch.countDown();
                return;
            }
            if (init) {
                MILLISECONDS.sleep(nextDelayMillis);
            } else {
                // retry delay doubles on average each time, randomized +/- 50%
                double randomizedDoubling = 0.5 + random.nextDouble();
                MILLISECONDS.sleep((long) (nextDelayMillis * randomizedDoubling));
            }
            nextDelayMillis = Math.min(nextDelayMillis * 2, maxSingleDelayMillis);
            grpcCall.call(RetryingStreamObserver.this);
        }

        private boolean retryOnError(Throwable t) {
            return init || !isResourceExhaustedException(t)
                    && stopwatch.elapsed(MILLISECONDS) < maxTotalMillis;
        }

        private boolean isResourceExhaustedException(Throwable t) {
            return t instanceof StatusRuntimeException
                    && ((StatusRuntimeException) t).getStatus() == Status.RESOURCE_EXHAUSTED;
        }
    }
}
