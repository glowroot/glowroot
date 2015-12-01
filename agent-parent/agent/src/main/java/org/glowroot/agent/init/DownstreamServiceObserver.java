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

import com.google.common.util.concurrent.RateLimiter;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ConfigUpdateResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ExceptionResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.UnknownRequestResponse;

class DownstreamServiceObserver implements StreamObserver<ServerRequest> {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceObserver.class);

    // limit error logging to once per minute
    private final RateLimiter loggingRateLimiter = RateLimiter.create(1 / 60.0);

    private final ConfigUpdateService configUpdateService;
    private final LiveJvmService liveJvmService;

    public volatile @MonotonicNonNull StreamObserver<ClientResponse> responseObserver;

    DownstreamServiceObserver(ConfigUpdateService configUpdateService,
            LiveJvmService liveJvmService) {
        this.configUpdateService = configUpdateService;
        this.liveJvmService = liveJvmService;
    }

    @Override
    public void onNext(ServerRequest request) {
        try {
            onNextInternal(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    private void onNextInternal(ServerRequest request) throws Exception {
        while (responseObserver == null) {
            Thread.sleep(10);
        }
        switch (request.getMessageCase()) {
            case CONFIG_UPDATE_REQUEST:
                updateConfigAndRespond(request);
                return;
            case REWEAVE_REQUEST:
                reweaveAndRespond(request);
                return;
            case THREAD_DUMP_REQUEST:
                threadDumpAndRespond(request);
                return;
            case AVAILABLE_DISK_SPACE_REQUEST:
                availableDiskSpaceAndRespond(request);
                return;
            case HEAP_DUMP_REQUEST:
                heapDumpAndRespond(request);
                return;
            case GC_REQUEST:
                gcAndRespond(request);
                return;
            case MBEAN_DUMP_REQUEST:
                mbeanDumpAndRespond(request);
                return;
            default:
                responseObserver.onNext(ClientResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setUnknownRequestResponse(UnknownRequestResponse.getDefaultInstance())
                        .build());
                return;
        }
    }

    @RequiresNonNull("responseObserver")
    private void updateConfigAndRespond(ServerRequest request) {
        try {
            configUpdateService.updateConfig(request.getConfigUpdateRequest().getConfig());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setConfigUpdateResponse(ConfigUpdateResponse.getDefaultInstance())
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void reweaveAndRespond(ServerRequest request) throws Exception {
        int classUpdateCount;
        try {
            classUpdateCount = configUpdateService.reweave();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setReweaveResponse(ReweaveResponse.newBuilder()
                        .setClassUpdateCount(classUpdateCount))
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void threadDumpAndRespond(ServerRequest request) {
        ThreadDump threadDump;
        try {
            threadDump = liveJvmService.getThreadDump("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setThreadDumpResponse(ThreadDumpResponse.newBuilder()
                        .setThreadDump(threadDump))
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void availableDiskSpaceAndRespond(ServerRequest request) {
        long availableDiskSpaceBytes;
        try {
            availableDiskSpaceBytes = liveJvmService.getAvailableDiskSpace("",
                    request.getAvailableDiskSpaceRequest().getDirectory());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setAvailableDiskSpaceResponse(AvailableDiskSpaceResponse.newBuilder()
                        .setAvailableBytes(availableDiskSpaceBytes))
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void heapDumpAndRespond(ServerRequest request) {
        HeapDumpFileInfo heapDumpFileInfo;
        try {
            heapDumpFileInfo =
                    liveJvmService.heapDump("", request.getHeapDumpRequest().getDirectory());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setHeapDumpResponse(HeapDumpResponse.newBuilder()
                        .setHeapDumpFileInfo(heapDumpFileInfo))
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void gcAndRespond(ServerRequest request) {
        try {
            liveJvmService.gc("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setGcResponse(GcResponse.getDefaultInstance())
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void mbeanDumpAndRespond(ServerRequest request) {
        MBeanDump mbeanDump;
        try {
            mbeanDump = liveJvmService.getMBeanDump("", request.getMbeanDumpRequest());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMbeanDumpResponse(MBeanDumpResponse.newBuilder()
                        .setMbeanDump(mbeanDump))
                .build());
    }

    @RequiresNonNull("responseObserver")
    private void sendExceptionResponse(ServerRequest request) {
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setExceptionResponse(ExceptionResponse.getDefaultInstance())
                .build());
    }

    @Override
    public void onError(Throwable t) {
        // limit error logging to once per minute
        if (loggingRateLimiter.tryAcquire()) {
            // this server error will not be sent back to the server (see GrpcLogbackAppender)
            GrpcCollector.logger.error(t.getMessage(), t);
        }
    }

    @Override
    public void onCompleted() {}
}
