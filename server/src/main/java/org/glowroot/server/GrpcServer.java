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
package org.glowroot.server;

import java.io.IOException;

import io.grpc.internal.ServerImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.server.storage.AgentDao;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.Proto;

class GrpcServer {

    private static final int GRPC_MAX_MESSAGE_SIZE_MB =
            Integer.getInteger("grpc.max.message.size.mb", 100);

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    private final AgentDao agentDao;
    private final AggregateRepository aggregateRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final TraceRepository traceRepository;
    private final AlertingService alertingService;

    private final DownstreamServiceImpl downstreamService;

    private final ServerImpl server;

    GrpcServer(int port, AgentDao agentDao, AggregateRepository aggregateRepository,
            GaugeValueRepository gaugeValueRepository, TraceRepository traceRepository,
            AlertingService alertingService) throws IOException {
        this.agentDao = agentDao;
        this.aggregateRepository = aggregateRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.traceRepository = traceRepository;
        this.alertingService = alertingService;

        downstreamService = new DownstreamServiceImpl();

        server = NettyServerBuilder.forPort(port)
                .addService(CollectorServiceGrpc.bindService(new CollectorServiceImpl()))
                .addService(DownstreamServiceGrpc.bindService(downstreamService))
                .maxMessageSize(1024 * 1024 * GRPC_MAX_MESSAGE_SIZE_MB)
                .build()
                .start();
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    void close() {
        server.shutdown();
    }

    private class CollectorServiceImpl implements CollectorService {

        @Override
        public void collectInit(InitMessage request,
                StreamObserver<InitResponse> responseObserver) {
            AgentConfig updatedAgentConfig;
            try {
                updatedAgentConfig = agentDao.store(request.getAgentId(), request.getSystemInfo(),
                        request.getAgentConfig());
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            if (updatedAgentConfig.equals(request.getAgentConfig())) {
                responseObserver.onNext(InitResponse.getDefaultInstance());
            } else {
                responseObserver.onNext(InitResponse.newBuilder()
                        .setAgentConfig(updatedAgentConfig)
                        .build());
            }
            responseObserver.onCompleted();
        }

        @Override
        public void collectAggregates(AggregateMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                aggregateRepository.store(request.getAgentId(), request.getCaptureTime(),
                        request.getAggregatesByTypeList());
                alertingService.checkTransactionAlerts(request.getAgentId(),
                        request.getCaptureTime());
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectGaugeValues(GaugeValueMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                gaugeValueRepository.store(request.getAgentId(), request.getGaugeValuesList());
                long maxCaptureTime = 0;
                for (GaugeValue gaugeValue : request.getGaugeValuesList()) {
                    maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
                }
                alertingService.checkGaugeAlerts(request.getAgentId(), maxCaptureTime);
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectTrace(TraceMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                traceRepository.collect(request.getAgentId(), request.getTrace());
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
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
                if (t == null) {
                    logger.warn("{} -- {} -- {} -- {}", request.getAgentId(), logEvent.getLevel(),
                            logEvent.getLoggerName(), logEvent.getMessage());
                } else {
                    logger.warn("{} -- {} -- {} -- {}\n{}", request.getAgentId(),
                            logEvent.getLevel(), logEvent.getLoggerName(),
                            logEvent.getMessage(), t);
                }
            } catch (Throwable t) {
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(EmptyMessage.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
