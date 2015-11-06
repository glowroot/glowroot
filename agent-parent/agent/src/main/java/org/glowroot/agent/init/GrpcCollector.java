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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceStub;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Hello;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.wire.api.model.LogEventOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcCollector implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(GrpcCollector.class);

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;
    private final CollectorServiceStub client;

    private final String serverId;
    private final ImmutableList<String> secondaryRollups;

    GrpcCollector(String serverId, List<String> secondaryRollups, String collectorHost,
            int collectorPort) {
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
        client = CollectorServiceGrpc.newStub(channel);
        this.serverId = serverId;
        this.secondaryRollups = ImmutableList.copyOf(secondaryRollups);
    }

    void hello() throws IOException {
        Hello hello = Hello.newBuilder()
                .setServerId(serverId)
                .addAllSecondaryRollup(secondaryRollups)
                .build();
        client.hello(hello, LoggingStreamObserver.INSTANCE);
    }

    @Override
    public void collectAggregates(long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws Exception {
        AggregateMessage aggregateMessage = AggregateMessage.newBuilder()
                .setServerId(serverId)
                .setCaptureTime(captureTime)
                .addAllOverallAggregate(overallAggregates)
                .addAllTransactionAggregate(transactionAggregates)
                .build();
        client.collectAggregates(aggregateMessage, LoggingStreamObserver.INSTANCE);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .addAllGaugeValues(gaugeValues)
                .build();
        client.collectGaugeValues(gaugeValueMessage, LoggingStreamObserver.INSTANCE);
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        TraceMessage traceMessage = TraceMessage.newBuilder()
                .setTrace(trace)
                .build();
        client.collectTrace(traceMessage, LoggingStreamObserver.INSTANCE);
    }

    @Override
    public void log(LogEvent logEvent) throws Exception {
        LogMessage logMessage = LogMessage.newBuilder()
                .setLogEvent(logEvent)
                .build();
        // don't use LoggingStreamObserver here to avoid recursive loop
        client.log(logMessage, NopStreamObserver.INSTANCE);
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown();
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

    private static class LoggingStreamObserver extends NopStreamObserver {

        private static final LoggingStreamObserver INSTANCE = new LoggingStreamObserver();

        @Override
        public void onError(Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    private static class NopStreamObserver implements StreamObserver<EmptyMessage> {

        private static final NopStreamObserver INSTANCE = new NopStreamObserver();

        @Override
        public void onNext(EmptyMessage value) {}

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }
}
