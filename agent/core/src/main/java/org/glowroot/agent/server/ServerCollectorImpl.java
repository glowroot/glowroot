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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.agent.server.ServerConnection.GrpcCall;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceStub;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent.Level;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkState;

public class ServerCollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(ServerCollectorImpl.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final String agentId;
    private final String collectorHost;
    private final int collectorPort;
    private final ServerConnection serverConnection;
    private final CollectorServiceStub collectorServiceStub;
    private final DownstreamServiceObserver downstreamServiceObserver;

    private final SharedQueryTextLimiter sharedQueryTextLimiter = new SharedQueryTextLimiter();

    public ServerCollectorImpl(Map<String, String> properties, String collectorHost,
            LiveJvmServiceImpl liveJvmService, LiveWeavingServiceImpl liveWeavingService,
            LiveTraceRepositoryImpl liveTraceRepository, AgentConfigUpdater agentConfigUpdater)
            throws Exception {

        String agentId = properties.get("glowroot.agent.id");
        if (Strings.isNullOrEmpty(agentId)) {
            agentId = InetAddress.getLocalHost().getHostName();
        }
        String collectorPortStr = properties.get("glowroot.collector.port");
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPortStr = System.getProperty("glowroot.collector.port");
        }
        int collectorPort;
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPort = 8181;
        } else {
            collectorPort = Integer.parseInt(collectorPortStr);
        }
        this.agentId = agentId;
        this.collectorHost = collectorHost;
        this.collectorPort = collectorPort;

        startupLogger.info("agent id: {}", agentId);

        AtomicBoolean inConnectionFailure = new AtomicBoolean();
        serverConnection = new ServerConnection(collectorHost, collectorPort, inConnectionFailure);
        collectorServiceStub = CollectorServiceGrpc.newStub(serverConnection.getChannel())
                .withCompression("gzip");
        downstreamServiceObserver = new DownstreamServiceObserver(serverConnection,
                agentConfigUpdater, liveJvmService, liveWeavingService, liveTraceRepository,
                agentId, inConnectionFailure, sharedQueryTextLimiter);
    }

    @Override
    public void init(File glowrootBaseDir, Environment environment, AgentConfig agentConfig,
            final AgentConfigUpdater agentConfigUpdater) {
        final InitMessage initMessage = InitMessage.newBuilder()
                .setAgentId(agentId)
                .setEnvironment(environment)
                .setAgentConfig(agentConfig)
                .build();
        serverConnection.callUntilSuccessful(new GrpcCall<InitResponse>() {
            @Override
            public void call(StreamObserver<InitResponse> responseObserver) {
                collectorServiceStub.collectInit(initMessage, responseObserver);
            }
            @Override
            void doWithResponse(final InitResponse response) {
                // don't need to suppress sending this log message to the server because startup
                // logger info messages are never sent to the server
                startupLogger.info("connected to server {}:{}, version {}", collectorHost,
                        collectorPort, response.getGlowrootServerVersion());
                if (response.hasAgentConfig()) {
                    try {
                        agentConfigUpdater.update(response.getAgentConfig());
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
                downstreamServiceObserver.connectAsync();
            }
        });
    }

    // collecting even when no aggregates since collection triggers transaction-based alerts
    @Override
    public void collectAggregates(final long captureTime, final Aggregates aggregates) {
        final List<String> fullTextSha1s = Lists.newArrayList();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                final StreamObserver<AggregateStreamMessage> requestObserver =
                        collectorServiceStub.collectAggregateStream(responseObserver);
                requestObserver.onNext(AggregateStreamMessage.newBuilder()
                        .setHeader(AggregateStreamHeader.newBuilder()
                                .setAgentId(agentId)
                                .setCaptureTime(captureTime))
                        .build());
                // need to clear in case this is a retry
                fullTextSha1s.clear();
                aggregates.accept(new AggregateVisitor<RuntimeException>() {
                    @Override
                    public void visitOverallAggregate(String transactionType,
                            List<String> sharedQueryTexts, Aggregate overallAggregate) {
                        for (String sharedQueryText : sharedQueryTexts) {
                            Aggregate.SharedQueryText aggregateSharedQueryText =
                                    sharedQueryTextLimiter
                                            .buildAggregateSharedQueryText(sharedQueryText);
                            String fullTextSha1 = aggregateSharedQueryText.getFullTextSha1();
                            if (!fullTextSha1.isEmpty()) {
                                fullTextSha1s.add(fullTextSha1);
                            }
                            requestObserver.onNext(AggregateStreamMessage.newBuilder()
                                    .setSharedQueryText(aggregateSharedQueryText)
                                    .build());
                        }
                        requestObserver.onNext(AggregateStreamMessage.newBuilder()
                                .setOverallAggregate(OverallAggregate.newBuilder()
                                        .setTransactionType(transactionType)
                                        .setAggregate(overallAggregate))
                                .build());
                    }
                    @Override
                    public void visitTransactionAggregate(String transactionType,
                            String transactionName, List<String> sharedQueryTexts,
                            Aggregate transactionAggregate) {
                        for (String sharedQueryText : sharedQueryTexts) {
                            requestObserver.onNext(AggregateStreamMessage.newBuilder()
                                    .setSharedQueryText(sharedQueryTextLimiter
                                            .buildAggregateSharedQueryText(sharedQueryText))
                                    .build());
                        }
                        requestObserver.onNext(AggregateStreamMessage.newBuilder()
                                .setTransactionAggregate(TransactionAggregate.newBuilder()
                                        .setTransactionType(transactionType)
                                        .setTransactionName(transactionName)
                                        .setAggregate(transactionAggregate))
                                .build());
                    }
                });
                requestObserver.onCompleted();
            }
            @Override
            public void doWithResponse(EmptyMessage response) {
                for (String fullTextSha1 : fullTextSha1s) {
                    sharedQueryTextLimiter.onSuccessfullySentToServer(fullTextSha1);
                }
            }
        });
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) {
        final GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .setAgentId(agentId)
                .addAllGaugeValues(gaugeValues)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectGaugeValues(gaugeValueMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectTrace(Trace trace) {
        final List<String> sharedQueryTexts = Lists.newArrayList();
        for (Trace.SharedQueryText sharedQueryText : trace.getSharedQueryTextList()) {
            // local collection always passes in full text
            checkState(sharedQueryText.getTruncatedText().isEmpty());
            checkState(sharedQueryText.getTruncatedEndText().isEmpty());
            checkState(sharedQueryText.getFullTextSha1().isEmpty());
            sharedQueryTexts.add(sharedQueryText.getFullText());
        }
        final Trace traceWithoutSharedQueryText = trace.toBuilder()
                .clearSharedQueryText()
                .build();
        final List<String> fullTextSha1s = Lists.newArrayList();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                StreamObserver<TraceStreamMessage> requestObserver =
                        collectorServiceStub.collectTraceStream(responseObserver);
                requestObserver.onNext(TraceStreamMessage.newBuilder()
                        .setHeader(TraceStreamHeader.newBuilder()
                                .setAgentId(agentId))
                        .build());
                // need to clear in case this is a retry
                fullTextSha1s.clear();
                for (String sharedQueryText : sharedQueryTexts) {
                    Trace.SharedQueryText traceSharedQueryText =
                            sharedQueryTextLimiter.buildTraceSharedQueryText(sharedQueryText);
                    String fullTextSha1 = traceSharedQueryText.getFullTextSha1();
                    if (!fullTextSha1.isEmpty()) {
                        fullTextSha1s.add(fullTextSha1);
                    }
                    requestObserver.onNext(TraceStreamMessage.newBuilder()
                            .setSharedQueryText(traceSharedQueryText)
                            .build());
                }
                requestObserver.onNext(TraceStreamMessage.newBuilder()
                        .setTrace(traceWithoutSharedQueryText)
                        .build());
                requestObserver.onCompleted();
            }
            @Override
            public void doWithResponse(EmptyMessage response) {
                for (String fullTextSha1 : fullTextSha1s) {
                    sharedQueryTextLimiter.onSuccessfullySentToServer(fullTextSha1);
                }
            }
        });
    }

    @Override
    public void log(LogEvent logEvent) {
        if (serverConnection.suppressLogCollector()) {
            return;
        }
        if (logEvent.getLoggerName().equals("org.glowroot") && logEvent.getLevel() == Level.INFO) {
            // never send startup logger info messages to server
            return;
        }
        final LogMessage logMessage = LogMessage.newBuilder()
                .setAgentId(agentId)
                .setLogEvent(logEvent)
                .build();
        serverConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.log(logMessage, responseObserver);
            }
        });
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        downstreamServiceObserver.close();
        serverConnection.close();
    }

    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        serverConnection.awaitClose();
    }
}
