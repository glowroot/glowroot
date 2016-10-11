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
package org.glowroot.central;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import com.datastax.driver.core.exceptions.ReadTimeoutException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.storage.AgentDao;
import org.glowroot.central.storage.AggregateDao;
import org.glowroot.central.storage.GaugeValueDao;
import org.glowroot.central.storage.TraceDao;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorServiceImplBase;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent.Level;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldAggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldTraceMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

class GrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final AgentDao agentDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final TraceDao traceDao;
    private final AlertingService alertingService;
    private final String version;

    private final DownstreamServiceImpl downstreamService;

    private final ServerImpl server;

    GrpcServer(String bindAddress, int port, AgentDao agentDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, TraceDao traceDao, AlertingService alertingService,
            String version) throws IOException {
        this.agentDao = agentDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.traceDao = traceDao;
        this.alertingService = alertingService;
        this.version = version;

        downstreamService = new DownstreamServiceImpl(agentDao);

        server = NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
                .addService(new CollectorServiceImpl().bindService())
                .addService(downstreamService.bindService())
                .build()
                .start();

        startupLogger.info("gRPC listening on {}:{}", bindAddress, port);
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    void close() {
        server.shutdown();
    }

    @VisibleForTesting
    static String trimSpacesAroundAgentRollupSeparator(String agentRollup) {
        return agentRollup.replaceAll(" */ *", "/").trim();
    }

    private class CollectorServiceImpl extends CollectorServiceImplBase {

        @Override
        public void collectInit(InitMessage request,
                StreamObserver<InitResponse> responseObserver) {
            AgentConfig updatedAgentConfig;
            try {
                String agentRollup = request.getAgentRollup();
                // trim spaces around rollup separator "/"
                agentRollup = trimSpacesAroundAgentRollupSeparator(agentRollup);
                updatedAgentConfig = agentDao.store(request.getAgentId(),
                        Strings.emptyToNull(agentRollup), request.getEnvironment(),
                        request.getAgentConfig());
            } catch (Throwable t) {
                logger.error("{} - {}", request.getAgentId(), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            logger.info("agent connected: {}, version {}", request.getAgentId(),
                    request.getEnvironment().getJavaInfo().getGlowrootAgentVersion());
            InitResponse.Builder response = InitResponse.newBuilder()
                    .setGlowrootCentralVersion(version);
            if (!updatedAgentConfig.equals(request.getAgentConfig())) {
                response.setAgentConfig(updatedAgentConfig);
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<AggregateStreamMessage> collectAggregateStream(
                final StreamObserver<EmptyMessage> responseObserver) {
            return new StreamObserver<AggregateStreamMessage>() {

                private @MonotonicNonNull AggregateStreamHeader header;
                private List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private Map<String, OldAggregatesByType.Builder> aggregatesByTypeMap =
                        Maps.newHashMap();

                @Override
                public void onNext(AggregateStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case HEADER:
                            header = value.getHeader();
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(value.getSharedQueryText());
                            break;
                        case OVERALL_AGGREGATE:
                            OverallAggregate overallAggregate = value.getOverallAggregate();
                            String transactionType = overallAggregate.getTransactionType();
                            aggregatesByTypeMap.put(transactionType,
                                    OldAggregatesByType.newBuilder()
                                            .setTransactionType(transactionType)
                                            .setOverallAggregate(overallAggregate.getAggregate()));
                            break;
                        case TRANSACTION_AGGREGATE:
                            TransactionAggregate transactionAggregate =
                                    value.getTransactionAggregate();
                            OldAggregatesByType.Builder builder = checkNotNull(aggregatesByTypeMap
                                    .get(transactionAggregate.getTransactionType()));
                            builder.addTransactionAggregate(OldTransactionAggregate.newBuilder()
                                    .setTransactionName(transactionAggregate.getTransactionName())
                                    .setAggregate(transactionAggregate.getAggregate())
                                    .build());
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unexpected message: " + value.getMessageCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (header == null) {
                        logger.error(t.getMessage(), t);
                    } else {
                        logger.error("{} - {}", header.getAgentId(), t.getMessage(), t);
                    }
                }

                @Override
                public void onCompleted() {
                    checkNotNull(header);
                    List<OldAggregatesByType> aggregatesByTypeList = Lists.newArrayList();
                    for (OldAggregatesByType.Builder aggregatesByType : aggregatesByTypeMap
                            .values()) {
                        aggregatesByTypeList.add(aggregatesByType.build());
                    }
                    collectAggregatesInternal(header.getAgentId(), header.getCaptureTime(),
                            sharedQueryTexts, aggregatesByTypeList, responseObserver);
                }
            };
        }

        @Override
        public void collectAggregates(OldAggregateMessage request,
                StreamObserver<EmptyMessage> responseObserver) {

            List<Aggregate.SharedQueryText> sharedQueryTexts;
            List<String> oldSharedQueryTexts = request.getOldSharedQueryTextList();
            if (oldSharedQueryTexts.isEmpty()) {
                sharedQueryTexts = request.getSharedQueryTextList();
            } else {
                // handle agents prior to 0.9.3
                sharedQueryTexts = Lists.newArrayList();
                for (String oldSharedQueryText : oldSharedQueryTexts) {
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setFullText(oldSharedQueryText)
                            .build());
                }
            }
            collectAggregatesInternal(request.getAgentId(), request.getCaptureTime(),
                    sharedQueryTexts, request.getAggregatesByTypeList(), responseObserver);
        }

        private void collectAggregatesInternal(String agentId, long captureTime,
                List<Aggregate.SharedQueryText> sharedQueryTexts,
                List<OldAggregatesByType> aggregatesByTypeList,
                StreamObserver<EmptyMessage> responseObserver) {
            if (!aggregatesByTypeList.isEmpty()) {
                try {
                    aggregateDao.store(agentId, captureTime, aggregatesByTypeList,
                            sharedQueryTexts);
                } catch (Throwable t) {
                    logger.error("{} - {}", agentId, t.getMessage(), t);
                    responseObserver.onError(t);
                    return;
                }
            }
            try {
                alertingService.checkTransactionAlerts(agentId, captureTime,
                        ReadTimeoutException.class);
            } catch (Throwable t) {
                logger.error("{} - {}", agentId, t.getMessage(), t);
                // don't fail collectAggregates()
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectGaugeValues(GaugeValueMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            long maxCaptureTime = 0;
            try {
                gaugeValueDao.store(request.getAgentId(), request.getGaugeValuesList());
                for (GaugeValue gaugeValue : request.getGaugeValuesList()) {
                    maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
                }
            } catch (Throwable t) {
                logger.error("{} - {}", request.getAgentId(), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            try {
                alertingService.checkGaugeAlerts(request.getAgentId(), maxCaptureTime,
                        ReadTimeoutException.class);
            } catch (Throwable t) {
                logger.error("{} - {}", request.getAgentId(), t.getMessage(), t);
                // don't fail collectGaugeValues()
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<TraceStreamMessage> collectTraceStream(
                final StreamObserver<EmptyMessage> responseObserver) {
            return new StreamObserver<TraceStreamMessage>() {

                private @MonotonicNonNull TraceStreamHeader header;
                private List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
                private @MonotonicNonNull Trace trace;

                @Override
                public void onNext(TraceStreamMessage value) {
                    switch (value.getMessageCase()) {
                        case HEADER:
                            header = value.getHeader();
                            break;
                        case SHARED_QUERY_TEXT:
                            sharedQueryTexts.add(value.getSharedQueryText());
                            break;
                        case TRACE:
                            trace = value.getTrace();
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unexpected message: " + value.getMessageCase());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (header == null) {
                        logger.error(t.getMessage(), t);
                    } else {
                        logger.error("{} - {}", header.getAgentId(), t.getMessage(), t);
                    }
                }

                @Override
                public void onCompleted() {
                    checkNotNull(header);
                    checkNotNull(trace);
                    try {
                        traceDao.store(header.getAgentId(), trace.toBuilder()
                                .addAllSharedQueryText(sharedQueryTexts)
                                .build());
                    } catch (Throwable t) {
                        logger.error("{} - {}", header.getAgentId(), t.getMessage(), t);
                        responseObserver.onError(t);
                        return;
                    }
                    responseObserver.onNext(EmptyMessage.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public void collectTrace(OldTraceMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                traceDao.store(request.getAgentId(), request.getTrace());
            } catch (Throwable t) {
                logger.error("{} - {}", request.getAgentId(), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void log(LogMessage request, StreamObserver<EmptyMessage> responseObserver) {
            try {
                LogEvent logEvent = request.getLogEvent();
                Proto.Throwable t = logEvent.getThrowable();
                Level level = logEvent.getLevel();
                if (t == null) {
                    log(level, "{} -- {} -- {} -- {}", request.getAgentId(), level,
                            logEvent.getLoggerName(), logEvent.getMessage());
                } else {
                    log(level, "{} -- {} -- {} -- {}\n{}", request.getAgentId(), level,
                            logEvent.getLoggerName(), logEvent.getMessage(), t);
                }
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private void log(Level level, String format, Object... arguments) {
            switch (level) {
                case ERROR:
                    logger.error(format, arguments);
                    break;
                case WARN:
                    logger.warn(format, arguments);
                    break;
                default:
                    logger.info(format, arguments);
                    break;
            }
        }
    }
}
