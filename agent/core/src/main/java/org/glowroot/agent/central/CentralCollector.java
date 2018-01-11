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
package org.glowroot.agent.central;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.central.CentralConnection.GrpcCall;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceStub;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
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
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamCounts;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class CentralCollector implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CentralCollector.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final String agentId;
    private final String collectorAddress;
    private final CentralConnection centralConnection;
    private final CollectorServiceStub collectorServiceStub;
    private final DownstreamServiceObserver downstreamServiceObserver;

    private final SharedQueryTextLimiter sharedQueryTextLimiter = new SharedQueryTextLimiter();

    private volatile int nextAggregateDelayMillis;

    public CentralCollector(Map<String, String> properties, String collectorAddress,
            @Nullable String collectorAuthority, File confDir, @Nullable File sharedConfDir,
            LiveJvmServiceImpl liveJvmService, LiveWeavingServiceImpl liveWeavingService,
            LiveTraceRepositoryImpl liveTraceRepository, AgentConfigUpdater agentConfigUpdater)
            throws Exception {

        String agentId = properties.get("glowroot.agent.id");
        if (Strings.isNullOrEmpty(agentId)) {
            agentId = escapeHostName(InetAddress.getLocalHost().getHostName());
        } else if (agentId.endsWith("::")) {
            agentId += escapeHostName(InetAddress.getLocalHost().getHostName());
        } else if (!agentId.contains("::")) {
            // check for 0.9.x agent rollup id
            String v09AgentRollupId = properties.get("glowroot.agent.rollup.id");
            if (!Strings.isNullOrEmpty(v09AgentRollupId)) {
                agentId = convertFromV09AgentRollupId(v09AgentRollupId) + agentId;
            }
        }
        this.agentId = agentId;
        this.collectorAddress = collectorAddress;

        startupLogger.info("agent id: {}", agentId);

        AtomicBoolean inConnectionFailure = new AtomicBoolean();
        centralConnection = new CentralConnection(collectorAddress, collectorAuthority, confDir,
                sharedConfDir, inConnectionFailure);
        collectorServiceStub = CollectorServiceGrpc.newStub(centralConnection.getChannel())
                .withCompression("gzip");
        downstreamServiceObserver = new DownstreamServiceObserver(centralConnection,
                agentConfigUpdater, liveJvmService, liveWeavingService, liveTraceRepository,
                agentId, inConnectionFailure, sharedQueryTextLimiter);
    }

    @Override
    public void init(File confDir, @Nullable File sharedConfDir, Environment environment,
            AgentConfig agentConfig, final AgentConfigUpdater agentConfigUpdater) {
        final InitMessage initMessage = InitMessage.newBuilder()
                .setAgentId(agentId)
                .setEnvironment(environment)
                .setAgentConfig(agentConfig)
                .build();
        centralConnection.callInit(new GrpcCall<InitResponse>() {
            @Override
            public void call(StreamObserver<InitResponse> responseObserver) {
                collectorServiceStub.collectInit(initMessage, responseObserver);
            }
            @Override
            void doWithResponse(final InitResponse response) {
                // don't need to suppress sending this log message to the central collector because
                // startup logger info messages are never sent to the central collector
                startupLogger.info("connected to the central collector {}, version {}",
                        collectorAddress, response.getGlowrootCentralVersion());
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
    public void collectAggregates(AggregateReader aggregateReader) {
        centralConnection.callWithAFewRetries(nextAggregateDelayMillis,
                new CollectAggregatesGrpcCall(aggregateReader));
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) {
        final GaugeValueMessage gaugeValueMessage = GaugeValueMessage.newBuilder()
                .setAgentId(agentId)
                .addAllGaugeValues(gaugeValues)
                .setPostV09(true)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.collectGaugeValues(gaugeValueMessage, responseObserver);
            }
        });
    }

    @Override
    public void collectTrace(TraceReader traceReader) {
        if (traceReader.partial()) {
            // do not retry partial transactions since they are live and reading from the trace
            // reader will not be idempotent, so could lead to confusing results
            centralConnection.callOnce(new CollectTraceGrpcCall(traceReader));
        } else {
            centralConnection.callWithAFewRetries(new CollectTraceGrpcCall(traceReader));
        }
    }

    @Override
    public void log(LogEvent logEvent) {
        if (centralConnection.suppressLogCollector()) {
            return;
        }
        if (logEvent.getLoggerName().equals("org.glowroot") && logEvent.getLevel() == Level.INFO) {
            // never send startup logger info messages to the central collector
            return;
        }
        final LogMessage logMessage = LogMessage.newBuilder()
                .setAgentId(agentId)
                .setLogEvent(logEvent)
                .setPostV09(true)
                .build();
        centralConnection.callWithAFewRetries(new GrpcCall<EmptyMessage>() {
            @Override
            public void call(StreamObserver<EmptyMessage> responseObserver) {
                collectorServiceStub.log(logMessage, responseObserver);
            }
        });
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        downstreamServiceObserver.close();
        centralConnection.close();
    }

    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        centralConnection.awaitClose();
    }

    @VisibleForTesting
    static String escapeHostName(String hostName) {
        hostName = hostName.replace("\\", "\\\\");
        if (hostName.startsWith(":")) {
            hostName = "\\" + hostName;
        }
        while (hostName.contains("::")) {
            hostName = hostName.replace("::", ":\\:");
        }
        return hostName;
    }

    private static String convertFromV09AgentRollupId(String agentRollupId) {
        // old agent rollup id supported spaces around separator
        return agentRollupId.replaceAll(" */ *", "::").trim() + "::";
    }

    private class CollectAggregatesGrpcCall extends GrpcCall<AggregateResponseMessage> {

        private class AggregateVisitorImpl implements AggregateVisitor {
            private final StreamObserver<AggregateStreamMessage> requestObserver;
            private AggregateVisitorImpl(StreamObserver<AggregateStreamMessage> requestObserver) {
                this.requestObserver = requestObserver;
            }
            @Override
            public void visitOverallAggregate(String transactionType,
                    List<String> sharedQueryTexts, Aggregate overallAggregate) {
                for (String sharedQueryText : sharedQueryTexts) {
                    Aggregate.SharedQueryText aggregateSharedQueryText = sharedQueryTextLimiter
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
        }

        private final AggregateReader aggregateReader;
        private final List<String> fullTextSha1s = Lists.newArrayList();

        private CollectAggregatesGrpcCall(AggregateReader aggregateReader) {
            this.aggregateReader = aggregateReader;
        }

        @Override
        public void call(StreamObserver<AggregateResponseMessage> responseObserver) {
            final StreamObserver<AggregateStreamMessage> requestObserver =
                    collectorServiceStub.collectAggregateStream(responseObserver);
            requestObserver.onNext(AggregateStreamMessage.newBuilder()
                    .setStreamHeader(AggregateStreamHeader.newBuilder()
                            .setAgentId(agentId)
                            .setCaptureTime(aggregateReader.captureTime())
                            .setPostV09(true))
                    .build());
            // need to clear in case this is a retry
            fullTextSha1s.clear();
            try {
                aggregateReader.accept(new AggregateVisitorImpl(requestObserver));
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                requestObserver.onError(t);
                return;
            }
            requestObserver.onCompleted();
        }

        @Override
        public void doWithResponse(AggregateResponseMessage response) {
            // Math.min is just for safety
            nextAggregateDelayMillis = Math.min(response.getNextDelayMillis(), 30000);
            for (String fullTextSha1 : fullTextSha1s) {
                sharedQueryTextLimiter.onSuccessfullySentToCentralCollector(fullTextSha1);
            }
        }
    }

    private class CollectTraceGrpcCall extends GrpcCall<EmptyMessage> {

        private final TraceReader traceReader;

        private final List<String> fullTextSha1s = Lists.newArrayList();

        private CollectTraceGrpcCall(TraceReader traceReader) {
            this.traceReader = traceReader;
        }

        @Override
        public void call(StreamObserver<EmptyMessage> responseObserver) {
            StreamObserver<TraceStreamMessage> requestObserver =
                    collectorServiceStub.collectTraceStream(responseObserver);
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setStreamHeader(TraceStreamHeader.newBuilder()
                            .setAgentId(agentId)
                            .setTraceId(traceReader.traceId())
                            .setUpdate(traceReader.update())
                            .setPostV09(true))
                    .build());
            // need to clear in case this is a retry
            fullTextSha1s.clear();
            TraceVisitorImpl traceVisitor = new TraceVisitorImpl(requestObserver, fullTextSha1s);
            try {
                traceReader.accept(traceVisitor);
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                requestObserver.onError(t);
                return;
            }
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setStreamCounts(TraceStreamCounts.newBuilder()
                            .setSharedQueryTextCount(traceVisitor.sharedQueryTextCount)
                            .setEntryCount(traceVisitor.entryCount))
                    .build());
            requestObserver.onCompleted();
        }

        @Override
        public void doWithResponse(EmptyMessage response) {
            for (String fullTextSha1 : fullTextSha1s) {
                sharedQueryTextLimiter.onSuccessfullySentToCentralCollector(fullTextSha1);
            }
        }
    }

    private class TraceVisitorImpl implements TraceVisitor {

        private final StreamObserver<TraceStreamMessage> requestObserver;
        private final List<String> fullTextSha1s;

        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();

        private int sharedQueryTextCount;
        private int entryCount;

        private TraceVisitorImpl(StreamObserver<TraceStreamMessage> requestObserver,
                List<String> fullTextSha1s) {
            this.requestObserver = requestObserver;
            this.fullTextSha1s = fullTextSha1s;
        }

        @Override
        public int visitSharedQueryText(String sharedQueryText) {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(sharedQueryText);
            if (sharedQueryTextIndex != null) {
                return sharedQueryTextIndex;
            }
            sharedQueryTextIndex = sharedQueryTextIndexes.size();
            sharedQueryTextIndexes.put(sharedQueryText, sharedQueryTextIndex);
            Trace.SharedQueryText traceSharedQueryText =
                    sharedQueryTextLimiter.buildTraceSharedQueryText(sharedQueryText);
            String fullTextSha1 = traceSharedQueryText.getFullTextSha1();
            if (!fullTextSha1.isEmpty()) {
                fullTextSha1s.add(fullTextSha1);
            }
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setSharedQueryText(traceSharedQueryText)
                    .build());
            sharedQueryTextCount++;
            return sharedQueryTextIndex;
        }

        @Override
        public void visitEntry(Trace.Entry entry) {
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setEntry(entry)
                    .build());
            entryCount++;
        }

        @Override
        public void visitMainThreadProfile(Profile profile) {
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setMainThreadProfile(profile)
                    .build());
        }

        @Override
        public void visitAuxThreadProfile(Profile profile) {
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setAuxThreadProfile(profile)
                    .build());
        }

        @Override
        public void visitHeader(Trace.Header header) {
            requestObserver.onNext(TraceStreamMessage.newBuilder()
                    .setHeader(header)
                    .build());
        }
    }
}
