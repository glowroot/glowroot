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
package org.glowroot.agent.central;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceStub;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ExceptionResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.UnknownRequestResponse;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

class DownstreamServiceObserver implements StreamObserver<ServerRequest> {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceObserver.class);

    private final CentralConnection centralConnection;
    private final DownstreamServiceStub downstreamServiceStub;
    private final ConfigUpdateService configUpdateService;
    private final LiveJvmService liveJvmService;
    private final String serverId;

    private volatile @Nullable StreamObserver<ClientResponse> currResponseObserver;
    private volatile boolean closedByServer;

    private final AtomicBoolean hasInitialConnection = new AtomicBoolean();
    private final AtomicBoolean inMaybeConnectionFailure = new AtomicBoolean();
    private final AtomicBoolean inConnectionFailure = new AtomicBoolean();

    DownstreamServiceObserver(CentralConnection centralConnection,
            ConfigUpdateService configUpdateService, LiveJvmService liveJvmService,
            String serverId) throws Exception {

        this.centralConnection = centralConnection;
        downstreamServiceStub = DownstreamServiceGrpc.newStub(centralConnection.getChannel());
        this.configUpdateService = configUpdateService;
        this.liveJvmService = liveJvmService;
        this.serverId = serverId;
    }

    @Override
    public void onNext(ServerRequest request) {
        final boolean initialConnect = !hasInitialConnection.getAndSet(true);
        inMaybeConnectionFailure.set(false);
        boolean errorFixed = inConnectionFailure.getAndSet(false);
        if (initialConnect || errorFixed) {
            centralConnection.suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    if (initialConnect) {
                        logger.info("connection has been established to glowroot central");
                    } else {
                        logger.info("connection has been re-established to glowroot central");
                    }
                }
            });
        }
        if (request.getMessageCase() == MessageCase.HELLO_ACK) {
            return;
        }
        try {
            onNextInternal(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public void onError(final Throwable t) {
        if (!inMaybeConnectionFailure.getAndSet(true)) {
            // one free pass
            // try immediate re-connect once in case this is just node of central cluster going down
            connectAsync();
            return;
        }
        if (!inConnectionFailure.getAndSet(true)) {
            centralConnection.suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    logger.warn("unable to connect to glowroot central (will keep trying...)");
                    logger.debug(t.getMessage(), t);
                }
            });
        }
        currResponseObserver = null;
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO revisit retry/backoff after next grpc version
                    Thread.sleep(1000);
                    connectAsync();
                } catch (final Throwable t) {
                    // intentionally capturing InterruptedException here as well to ensure reconnect
                    // is attempted no matter what
                    centralConnection.suppressLogCollector(new Runnable() {
                        @Override
                        public void run() {
                            logger.error(t.getMessage(), t);
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onCompleted() {
        closedByServer = true;
    }

    void connectAsync() {
        // these are async so never fail, onError() will be called on failure
        StreamObserver<ClientResponse> responseObserver = downstreamServiceStub.connect(this);
        currResponseObserver = responseObserver;
        responseObserver.onNext(ClientResponse.newBuilder()
                .setHello(Hello.newBuilder()
                        .setServerId(serverId))
                .build());
    }

    private void onNextInternal(ServerRequest request) throws Exception {
        StreamObserver<ClientResponse> responseObserver = currResponseObserver;
        while (responseObserver == null) {
            Thread.sleep(10);
            responseObserver = currResponseObserver;
        }
        switch (request.getMessageCase()) {
            case AGENT_CONFIG_UPDATE_REQUEST:
                updateConfigAndRespond(request, responseObserver);
                return;
            case REWEAVE_REQUEST:
                reweaveAndRespond(request, responseObserver);
                return;
            case THREAD_DUMP_REQUEST:
                threadDumpAndRespond(request, responseObserver);
                return;
            case AVAILABLE_DISK_SPACE_REQUEST:
                availableDiskSpaceAndRespond(request, responseObserver);
                return;
            case HEAP_DUMP_REQUEST:
                heapDumpAndRespond(request, responseObserver);
                return;
            case GC_REQUEST:
                gcAndRespond(request, responseObserver);
                return;
            case MBEAN_DUMP_REQUEST:
                mbeanDumpAndRespond(request, responseObserver);
                return;
            default:
                responseObserver.onNext(ClientResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setUnknownRequestResponse(UnknownRequestResponse.getDefaultInstance())
                        .build());
                return;
        }
    }

    private void updateConfigAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        try {
            configUpdateService
                    .updateAgentConfig(request.getAgentConfigUpdateRequest().getAgentConfig());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setAgentConfigUpdateResponse(AgentConfigUpdateResponse.getDefaultInstance())
                .build());
    }

    private void reweaveAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        int classUpdateCount;
        try {
            classUpdateCount = configUpdateService.reweave();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setReweaveResponse(ReweaveResponse.newBuilder()
                        .setClassUpdateCount(classUpdateCount))
                .build());
    }

    private void threadDumpAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        ThreadDump threadDump;
        try {
            threadDump = liveJvmService.getThreadDump("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setThreadDumpResponse(ThreadDumpResponse.newBuilder()
                        .setThreadDump(threadDump))
                .build());
    }

    private void availableDiskSpaceAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        long availableDiskSpaceBytes;
        try {
            availableDiskSpaceBytes = liveJvmService.getAvailableDiskSpace("",
                    request.getAvailableDiskSpaceRequest().getDirectory());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setAvailableDiskSpaceResponse(AvailableDiskSpaceResponse.newBuilder()
                        .setAvailableBytes(availableDiskSpaceBytes))
                .build());
    }

    private void heapDumpAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        HeapDumpFileInfo heapDumpFileInfo;
        try {
            heapDumpFileInfo =
                    liveJvmService.heapDump("", request.getHeapDumpRequest().getDirectory());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setHeapDumpResponse(HeapDumpResponse.newBuilder()
                        .setHeapDumpFileInfo(heapDumpFileInfo))
                .build());
    }

    private void gcAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        try {
            liveJvmService.gc("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setGcResponse(GcResponse.getDefaultInstance())
                .build());
    }

    private void mbeanDumpAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MBeanDump mbeanDump;
        try {
            mbeanDump = liveJvmService.getMBeanDump("", request.getMbeanDumpRequest());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMbeanDumpResponse(MBeanDumpResponse.newBuilder()
                        .setMbeanDump(mbeanDump))
                .build());
    }

    private void sendExceptionResponse(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setExceptionResponse(ExceptionResponse.getDefaultInstance())
                .build());
    }

    @OnlyUsedByTests
    void close() throws InterruptedException {
        StreamObserver<ClientResponse> responseObserver = currResponseObserver;
        while (responseObserver == null) {
            Thread.sleep(10);
            responseObserver = currResponseObserver;
        }
        responseObserver.onCompleted();
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 10 && !closedByServer) {
            Thread.sleep(10);
        }
        checkState(closedByServer);
    }
}
