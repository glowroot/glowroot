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

import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.storage.ServerDao;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.Proto;

public class GrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    private final ServerDao serverDao;
    private final AggregateRepository aggregateRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final TraceRepository traceRepository;

    private final DownstreamServiceImpl downstreamService;

    public GrpcServer(int port, ServerDao serverDao, AggregateRepository aggregateRepository,
            GaugeValueRepository gaugeValueRepository, TraceRepository traceRepository)
                    throws IOException {
        this.serverDao = serverDao;
        this.aggregateRepository = aggregateRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.traceRepository = traceRepository;

        downstreamService = new DownstreamServiceImpl();

        NettyServerBuilder.forPort(port)
                .addService(CollectorServiceGrpc.bindService(new CollectorServiceImpl()))
                .addService(DownstreamServiceGrpc.bindService(downstreamService))
                .build()
                .start();
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    private class CollectorServiceImpl implements CollectorService {

        @Override
        public void collectInit(InitMessage request,
                StreamObserver<InitResponse> responseObserver) {
            try {
                serverDao.store(request.getServerId(), request.getProcessInfo(),
                        request.getAgentConfig());
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
            // FIXME return agent config
            responseObserver.onNext(InitResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void collectAggregates(AggregateMessage request,
                StreamObserver<EmptyMessage> responseObserver) {
            try {
                aggregateRepository.store(request.getServerId(), request.getCaptureTime(),
                        request.getAggregatesByTypeList());
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
                gaugeValueRepository.store(request.getServerId(), request.getGaugeValuesList());
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
                traceRepository.collect(request.getServerId(), request.getTrace());
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
                // FIXME put these in Cassandra and render in central UI
                LogEvent logEvent = request.getLogEvent();
                Proto.Throwable t = logEvent.getThrowable();
                if (t == null) {
                    logger.warn("{} -- {} -- {} -- {}", request.getServerId(), logEvent.getLevel(),
                            logEvent.getLoggerName(), logEvent.getFormattedMessage());
                } else {
                    logger.warn("{} -- {} -- {} -- {}\n{}", request.getServerId(),
                            logEvent.getLevel(), logEvent.getLoggerName(),
                            logEvent.getFormattedMessage(), t);
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
