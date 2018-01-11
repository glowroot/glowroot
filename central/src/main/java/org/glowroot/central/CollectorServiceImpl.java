/*
 * Copyright 2017 the original author or authors.
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.SchemaUpgrade;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.V09AgentRollupDao;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
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
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamCounts;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamHeader;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceStreamMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

class CollectorServiceImpl extends CollectorServiceGrpc.CollectorServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(CollectorServiceImpl.class);

    private final AgentDao agentDao;
    private final AgentConfigDao agentConfigDao;
    private final EnvironmentDao environmentDao;
    private final HeartbeatDao heartbeatDao;
    private final AggregateDao aggregateDao;
    private final GaugeValueDao gaugeValueDao;
    private final TraceDao traceDao;
    private final V09AgentRollupDao v09AgentRollupDao;
    private final CentralAlertingService centralAlertingService;
    private final Clock clock;
    private final String version;

    private volatile long currentMinute;
    private final AtomicInteger nextDelay = new AtomicInteger();

    CollectorServiceImpl(AgentDao agentDao, AgentConfigDao agentConfigDao,
            EnvironmentDao environmentDao, HeartbeatDao heartbeatDao, AggregateDao aggregateDao,
            GaugeValueDao gaugeValueDao, TraceDao traceDao, V09AgentRollupDao v09AgentRollupDao,
            CentralAlertingService centralAlertingService, Clock clock, String version) {
        this.agentDao = agentDao;
        this.agentConfigDao = agentConfigDao;
        this.environmentDao = environmentDao;
        this.heartbeatDao = heartbeatDao;
        this.aggregateDao = aggregateDao;
        this.gaugeValueDao = gaugeValueDao;
        this.traceDao = traceDao;
        this.v09AgentRollupDao = v09AgentRollupDao;
        this.centralAlertingService = centralAlertingService;
        this.clock = clock;
        this.version = version;
    }

    @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Init",
            traceHeadline = "Collect init: {{0.agentId}}", timer = "init")
    @Override
    public void collectInit(InitMessage request, StreamObserver<InitResponse> responseObserver) {
        String agentId = request.getAgentId();
        String v09AgentRollupId = request.getV09AgentRollupId();
        if (!v09AgentRollupId.isEmpty()) {
            // handle agents prior to 0.10.0
            String v09AgentId = agentId;
            agentId = convertFromV09AgentRollupId(v09AgentRollupId) + v09AgentId;
            try {
                v09AgentRollupDao.store(v09AgentId, v09AgentRollupId);
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
        }
        AgentConfig updatedAgentConfig;
        try {
            updatedAgentConfig = SchemaUpgrade.upgradeOldAgentConfig(request.getAgentConfig());
            updatedAgentConfig = agentConfigDao.store(agentId, updatedAgentConfig);
            environmentDao.store(agentId, request.getEnvironment());
            agentDao.insert(agentId, clock.currentTimeMillis());
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        logger.info("agent connected: {}, version {}", getDisplayForLogging(agentId),
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
            StreamObserver<AggregateResponseMessage> responseObserver) {
        return new AggregateStreamObserver(responseObserver);
    }

    @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Aggregates",
            traceHeadline = "Collect aggregates: {{0.agentId}}", timer = "aggregates")
    @Override
    public void collectAggregates(OldAggregateMessage request,
            StreamObserver<AggregateResponseMessage> responseObserver) {
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
        String agentId;
        try {
            agentId = getAgentId(request.getAgentId(), false);
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(request.getAgentId(), false),
                    t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        throttledCollectAggregates(agentId, request.getCaptureTime(),
                sharedQueryTexts, request.getAggregatesByTypeList(), responseObserver);
    }

    @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Gauges",
            traceHeadline = "Collect gauge values: {{0.agentId}}", timer = "gauges")
    @Override
    public void collectGaugeValues(GaugeValueMessage request,
            StreamObserver<EmptyMessage> responseObserver) {
        throttledCollectGaugeValues(request, responseObserver);
    }

    @Override
    public StreamObserver<TraceStreamMessage> collectTraceStream(
            StreamObserver<EmptyMessage> responseObserver) {
        return new TraceStreamObserver(responseObserver);
    }

    @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Trace",
            traceHeadline = "Collect trace: {{0.agentId}}", timer = "trace")
    @Override
    public void collectTrace(OldTraceMessage request,
            StreamObserver<EmptyMessage> responseObserver) {
        String agentId;
        try {
            agentId = getAgentId(request.getAgentId(), false);
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(request.getAgentId(), false),
                    t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        try {
            traceDao.store(agentId, request.getTrace());
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(EmptyMessage.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Log",
            traceHeadline = "Log: {{0.agentId}}", timer = "log")
    @Override
    public void log(LogMessage request, StreamObserver<EmptyMessage> responseObserver) {
        String agentId;
        try {
            agentId = getAgentId(request.getAgentId(), request.getPostV09());
        } catch (Throwable t) {
            logger.error("{} - {}",
                    getDisplayForLogging(request.getAgentId(), request.getPostV09()),
                    t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        try {
            LogEvent logEvent = request.getLogEvent();
            Level level = logEvent.getLevel();
            String agentDisplay = agentDao.readAgentRollupDisplay(agentId);
            String formattedTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                    .format(new Date(logEvent.getTimestamp()));
            if (logEvent.hasThrowable()) {
                log(level, "{} - {} {} {} - {}\n{}", agentDisplay, formattedTimestamp,
                        level, logEvent.getLoggerName(), logEvent.getMessage(),
                        toString(logEvent.getThrowable()));
            } else {
                log(level, "{} - {} {} {} - {}", agentDisplay, formattedTimestamp, level,
                        logEvent.getLoggerName(), logEvent.getMessage());
            }
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(EmptyMessage.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private void throttledCollectAggregates(String agentId, long captureTime,
            List<Aggregate.SharedQueryText> sharedQueryTexts,
            List<OldAggregatesByType> aggregatesByTypeList,
            StreamObserver<AggregateResponseMessage> responseObserver) {
        if (!aggregatesByTypeList.isEmpty()) {
            try {
                aggregateDao.store(agentId, captureTime, aggregatesByTypeList, sharedQueryTexts);
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
        }
        String agentDisplay;
        try {
            agentDisplay = agentDao.readAgentRollupDisplay(agentId);
        } catch (Exception e) {
            logger.error("{} - {}", getDisplayForLogging(agentId), e.getMessage(), e);
            responseObserver.onError(e);
            return;
        }
        try {
            centralAlertingService.checkForDeletedAlerts(agentId, agentDisplay);
            centralAlertingService.checkAggregateAlertsAsync(agentId, agentDisplay, captureTime);
        } catch (InterruptedException e) {
            // probably shutdown requested
            logger.debug(e.getMessage(), e);
        }
        responseObserver.onNext(AggregateResponseMessage.newBuilder()
                .setNextDelayMillis(getNextDelayMillis())
                .build());
        responseObserver.onCompleted();
    }

    private void throttledCollectGaugeValues(GaugeValueMessage request,
            StreamObserver<EmptyMessage> responseObserver) {
        String agentId;
        try {
            agentId = getAgentId(request.getAgentId(), request.getPostV09());
        } catch (Throwable t) {
            logger.error("{} - {}",
                    getDisplayForLogging(request.getAgentId(), request.getPostV09()),
                    t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        long maxCaptureTime = 0;
        try {
            gaugeValueDao.store(agentId, request.getGaugeValuesList());
            for (GaugeValue gaugeValue : request.getGaugeValuesList()) {
                maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
            }
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        try {
            heartbeatDao.store(agentId);
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        String agentDisplay;
        try {
            agentDisplay = agentDao.readAgentRollupDisplay(agentId);
        } catch (Throwable t) {
            logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        try {
            centralAlertingService.checkForDeletedAlerts(agentId, agentDisplay);
            centralAlertingService.checkGaugeAndHeartbeatAlertsAsync(agentId, agentDisplay,
                    maxCaptureTime);
        } catch (InterruptedException e) {
            // probably shutdown requested
            logger.debug(e.getMessage(), e);
        }
        responseObserver.onNext(EmptyMessage.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private int getNextDelayMillis() {
        long currentishTimeMillis = clock.currentTimeMillis() + 10000;
        if (currentishTimeMillis > currentMinute) {
            // race condition here is ok, at worst results in resetting nextDelay multiple times
            nextDelay.set(0);
            currentMinute = (long) Math.ceil(currentishTimeMillis / 60000.0) * 60000;
        }
        // spread out aggregate collections 100 milliseconds a part, rolling over at 10 seconds
        return nextDelay.getAndAdd(100) % 10000;
    }

    private String getDisplayForLogging(String agentId, boolean postV09) {
        if (postV09) {
            return getDisplayForLogging(agentId);
        }
        String postV09AgentId;
        try {
            postV09AgentId = getAgentId(agentId, false);
        } catch (Exception e) {
            logger.error("{} - v09:{}", agentId, e.getMessage(), e);
            return "id:v09:" + agentId;
        }
        return getDisplayForLogging(postV09AgentId);
    }

    private String getDisplayForLogging(String agentId) {
        try {
            return agentDao.readAgentRollupDisplay(agentId);
        } catch (Exception e) {
            logger.error("{} - {}", agentId, e.getMessage(), e);
            return "id:" + agentId;
        }
    }

    private String getAgentId(String agentId, boolean postV09) throws Exception {
        if (postV09) {
            return agentId;
        }
        String v09AgentRollupId = v09AgentRollupDao.getV09AgentRollupId(agentId);
        if (v09AgentRollupId == null) {
            return agentId;
        } else {
            return convertFromV09AgentRollupId(v09AgentRollupId) + agentId;
        }
    }

    private static String toString(Proto.Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClassName());
        String message = t.getMessage();
        if (!message.isEmpty()) {
            sb.append(": ");
            sb.append(message);
        }
        for (Proto.StackTraceElement stackTraceElement : t.getStackTraceElementList()) {
            sb.append("\n\tat ");
            sb.append(new StackTraceElement(stackTraceElement.getClassName(),
                    stackTraceElement.getMethodName(), stackTraceElement.getFileName(),
                    stackTraceElement.getLineNumber()));
        }
        int framesInCommonWithEnclosing = t.getFramesInCommonWithEnclosing();
        if (framesInCommonWithEnclosing > 0) {
            sb.append("\n\t... ");
            sb.append(framesInCommonWithEnclosing);
            sb.append(" more");
        }
        if (t.hasCause()) {
            sb.append("\nCaused by: ");
            sb.append(toString(t.getCause()));
        }
        return sb.toString();
    }

    private static void log(Level level, String format, Object... arguments) {
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

    @VisibleForTesting
    static String convertFromV09AgentRollupId(String v09AgentRollupId) {
        // old agent rollup id supported spaces around separator
        return v09AgentRollupId.replaceAll(" */ *", "::").trim() + "::";
    }

    private final class AggregateStreamObserver implements StreamObserver<AggregateStreamMessage> {

        private final StreamObserver<AggregateResponseMessage> responseObserver;
        private @MonotonicNonNull AggregateStreamHeader streamHeader;
        private List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        private Map<String, OldAggregatesByType.Builder> aggregatesByTypeMap = Maps.newHashMap();

        private AggregateStreamObserver(StreamObserver<AggregateResponseMessage> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onNext(AggregateStreamMessage value) {
            switch (value.getMessageCase()) {
                case STREAM_HEADER:
                    streamHeader = value.getStreamHeader();
                    break;
                case SHARED_QUERY_TEXT:
                    sharedQueryTexts.add(value.getSharedQueryText());
                    break;
                case OVERALL_AGGREGATE:
                    OverallAggregate overallAggregate = value.getOverallAggregate();
                    String transactionType = overallAggregate.getTransactionType();
                    aggregatesByTypeMap.put(transactionType, OldAggregatesByType.newBuilder()
                            .setTransactionType(transactionType)
                            .setOverallAggregate(overallAggregate.getAggregate()));
                    break;
                case TRANSACTION_AGGREGATE:
                    TransactionAggregate transactionAggregate = value.getTransactionAggregate();
                    OldAggregatesByType.Builder builder = checkNotNull(
                            aggregatesByTypeMap.get(transactionAggregate.getTransactionType()));
                    builder.addTransactionAggregate(OldTransactionAggregate.newBuilder()
                            .setTransactionName(transactionAggregate.getTransactionName())
                            .setAggregate(transactionAggregate.getAggregate())
                            .build());
                    break;
                default:
                    throw new RuntimeException("Unexpected message: " + value.getMessageCase());
            }
        }

        @Override
        public void onError(Throwable t) {
            if (streamHeader == null) {
                logger.error(t.getMessage(), t);
            } else {
                logger.error("{} - {}",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        t.getMessage(), t);
            }
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Aggregates",
                traceHeadline = "Collect aggregates: {{this.streamHeader.agentId}}",
                timer = "aggregates")
        @Override
        public void onCompleted() {
            checkNotNull(streamHeader);
            List<OldAggregatesByType> aggregatesByTypeList = Lists.newArrayList();
            for (OldAggregatesByType.Builder aggregatesByType : aggregatesByTypeMap.values()) {
                aggregatesByTypeList.add(aggregatesByType.build());
            }
            String agentId;
            try {
                agentId = getAgentId(streamHeader.getAgentId(), streamHeader.getPostV09());
            } catch (Throwable t) {
                logger.error("{} - {}",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            throttledCollectAggregates(agentId, streamHeader.getCaptureTime(), sharedQueryTexts,
                    aggregatesByTypeList, responseObserver);
        }
    }

    private final class TraceStreamObserver implements StreamObserver<TraceStreamMessage> {

        private final StreamObserver<EmptyMessage> responseObserver;
        private @MonotonicNonNull TraceStreamHeader streamHeader;
        private List<Trace.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        private @MonotonicNonNull Trace trace;
        private List<Trace.Entry> entries = Lists.newArrayList();
        private @MonotonicNonNull Profile mainThreadProfile;
        private @MonotonicNonNull Profile auxThreadProfile;
        private Trace. /*@MonotonicNonNull*/ Header header;
        private @MonotonicNonNull TraceStreamCounts streamCounts;

        private TraceStreamObserver(StreamObserver<EmptyMessage> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onNext(TraceStreamMessage value) {
            switch (value.getMessageCase()) {
                case STREAM_HEADER:
                    streamHeader = value.getStreamHeader();
                    break;
                case SHARED_QUERY_TEXT:
                    sharedQueryTexts.add(value.getSharedQueryText());
                    break;
                case TRACE:
                    // this is for 0.9.12 and prior agents
                    trace = value.getTrace();
                    break;
                case ENTRY:
                    entries.add(value.getEntry());
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
                    streamCounts = value.getStreamCounts();
                    break;
                default:
                    throw new RuntimeException("Unexpected message: " + value.getMessageCase());
            }
        }

        @Override
        public void onError(Throwable t) {
            if (streamHeader == null) {
                logger.error(t.getMessage(), t);
            } else {
                logger.error("{} - {}",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        t.getMessage(), t);
            }
        }

        @Instrumentation.Transaction(transactionType = "gRPC", transactionName = "Trace",
                traceHeadline = "Collect trace: {{this.streamHeader.agentId}}", timer = "trace")
        @Override
        public void onCompleted() {
            checkNotNull(streamHeader);
            if (trace == null) {
                // this is for 0.9.13 and later agents
                checkNotNull(streamCounts);
                if (!isEverythingReceived()) {
                    // no point in calling onError to force re-try since gRPC maxMessageSize limit
                    // will just be hit again
                    responseObserver.onNext(EmptyMessage.getDefaultInstance());
                    responseObserver.onCompleted();
                    return;
                }
                Trace.Builder builder = Trace.newBuilder()
                        .setId(streamHeader.getTraceId())
                        .setUpdate(streamHeader.getUpdate())
                        .setHeader(checkNotNull(header))
                        .addAllEntry(entries)
                        .addAllSharedQueryText(sharedQueryTexts);
                if (mainThreadProfile != null) {
                    builder.setMainThreadProfile(mainThreadProfile);
                }
                if (auxThreadProfile != null) {
                    builder.setAuxThreadProfile(auxThreadProfile);
                }
                trace = builder.build();
            } else {
                trace = trace.toBuilder()
                        .addAllSharedQueryText(sharedQueryTexts)
                        .build();
            }
            String agentId;
            try {
                agentId = getAgentId(streamHeader.getAgentId(), streamHeader.getPostV09());
            } catch (Throwable t) {
                logger.error("{} - {}",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            try {
                traceDao.store(agentId, trace);
            } catch (Throwable t) {
                logger.error("{} - {}", getDisplayForLogging(agentId), t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @RequiresNonNull({"streamHeader", "streamCounts"})
        private boolean isEverythingReceived() {
            // validate that all data was received, may not receive everything due to gRPC
            // maxMessageSize limit exceeded: "Compressed frame exceeds maximum frame size"
            if (header == null) {
                logger.error("{} - did not receive header, likely due to gRPC maxMessageSize limit"
                        + " exceeded",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()));
                return false;
            }
            if (sharedQueryTexts.size() < streamCounts.getSharedQueryTextCount()) {
                logger.error(
                        "{} - expected {} shared query texts, but only received {}, likely due to"
                                + " gRPC maxMessageSize limit exceeded for some of them",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        streamCounts.getSharedQueryTextCount(), sharedQueryTexts.size());
                return false;
            }
            if (entries.size() < streamCounts.getEntryCount()) {
                logger.error("{} - expected {} entries, but only received {}, likely due to gRPC"
                        + " maxMessageSize limit exceeded for some of them",
                        getDisplayForLogging(streamHeader.getAgentId(), streamHeader.getPostV09()),
                        streamCounts.getEntryCount(), entries.size());
                return false;
            }
            checkState(sharedQueryTexts.size() == streamCounts.getSharedQueryTextCount());
            checkState(entries.size() == streamCounts.getEntryCount());
            return true;
        }
    }
}
