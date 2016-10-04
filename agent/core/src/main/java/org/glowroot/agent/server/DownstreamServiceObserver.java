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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.common.live.LiveJvmService.DirectoryDoesNotExistException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInJreException;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceStub;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AuxThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CapabilitiesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.EntriesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ExceptionResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.FullTraceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMetaResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeaderResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.JstackResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMetaResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MainThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingClassNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingClassNamesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMBeanObjectNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMBeanObjectNamesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMethodNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMethodNamesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignaturesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignaturesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.PreloadClasspathCacheResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.SystemPropertiesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.UnknownRequestResponse;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

class DownstreamServiceObserver implements StreamObserver<ServerRequest> {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceObserver.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final ServerConnection serverConnection;
    private final DownstreamServiceStub downstreamServiceStub;
    private final AgentConfigUpdater agentConfigUpdater;
    private final LiveJvmServiceImpl liveJvmService;
    private final LiveWeavingServiceImpl liveWeavingService;
    private final LiveTraceRepositoryImpl liveTraceRepository;
    private final String agentId;

    private volatile @Nullable StreamObserver<ClientResponse> currResponseObserver;
    private volatile boolean closedByServer;

    private final AtomicBoolean hasInitialConnection = new AtomicBoolean();
    private final AtomicBoolean inMaybeConnectionFailure = new AtomicBoolean();
    private final AtomicBoolean inConnectionFailure;

    private final SharedQueryTextLimiter sharedQueryTextLimiter;

    DownstreamServiceObserver(ServerConnection serverConnection,
            AgentConfigUpdater agentConfigUpdater, LiveJvmServiceImpl liveJvmService,
            LiveWeavingServiceImpl liveWeavingService, LiveTraceRepositoryImpl liveTraceRepository,
            String agentId, AtomicBoolean inConnectionFailure,
            SharedQueryTextLimiter sharedQueryTextLimiter) throws Exception {
        this.serverConnection = serverConnection;
        downstreamServiceStub = DownstreamServiceGrpc.newStub(serverConnection.getChannel())
                .withCompression("gzip");
        this.agentConfigUpdater = agentConfigUpdater;
        this.liveJvmService = liveJvmService;
        this.liveWeavingService = liveWeavingService;
        this.liveTraceRepository = liveTraceRepository;
        this.agentId = agentId;
        this.inConnectionFailure = inConnectionFailure;
        this.sharedQueryTextLimiter = sharedQueryTextLimiter;
    }

    @Override
    public void onNext(ServerRequest request) {
        final boolean initialConnect = !hasInitialConnection.getAndSet(true);
        inMaybeConnectionFailure.set(false);
        boolean errorFixed = inConnectionFailure.getAndSet(false);
        if (initialConnect || errorFixed) {
            if (initialConnect) {
                // don't need to suppress sending this log message to the server because startup
                // logger info messages are never sent to the server
                startupLogger.info("downstream connection established with server");
            } else {
                // don't need to suppress sending this log message to the server because startup
                // logger info messages are never sent to the server
                startupLogger.info("downstream connection re-established with server");
            }
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
            // try immediate re-connect once in case this is just node of glowroot server cluster
            // going down
            connectAsync();
            return;
        }
        if (!inConnectionFailure.getAndSet(true)) {
            serverConnection.suppressLogCollector(new Runnable() {
                @Override
                public void run() {
                    startupLogger.warn("unable to establish downstream connection with server"
                            + " (will keep trying): {}", t.getMessage());
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
                    serverConnection.suppressLogCollector(new Runnable() {
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
                        .setAgentId(agentId))
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
            case THREAD_DUMP_REQUEST:
                threadDumpAndRespond(request, responseObserver);
                return;
            case JSTACK_REQUEST:
                jstackAndRespond(request, responseObserver);
                return;
            case AVAILABLE_DISK_SPACE_REQUEST:
                availableDiskSpaceAndRespond(request, responseObserver);
                return;
            case HEAP_DUMP_REQUEST:
                heapDumpAndRespond(request, responseObserver);
                return;
            case HEAP_HISTOGRAM_REQUEST:
                heapHistogramAndRespond(request, responseObserver);
                return;
            case GC_REQUEST:
                gcAndRespond(request, responseObserver);
                return;
            case MBEAN_DUMP_REQUEST:
                mbeanDumpAndRespond(request, responseObserver);
                return;
            case MATCHING_MBEAN_OBJECT_NAMES_REQUEST:
                matchingMBeanObjectNamesAndRespond(request, responseObserver);
                return;
            case MBEAN_META_REQUEST:
                mbeanMetaAndRespond(request, responseObserver);
                return;
            case SYSTEM_PROPERTIES_REQUEST:
                systemPropertiesAndRespond(request, responseObserver);
                return;
            case CAPABILITIES_REQUEST:
                capabilitiesAndRespond(request, responseObserver);
                return;
            case GLOBAL_META_REQUEST:
                globalMetaAndRespond(request, responseObserver);
                return;
            case PRELOAD_CLASSPATH_CACHE_REQUEST:
                preloadClasspathCacheAndRespond(request, responseObserver);
                return;
            case MATCHING_CLASS_NAMES_REQUEST:
                matchingClassNamesAndRespond(request, responseObserver);
                return;
            case MATCHING_METHOD_NAMES_REQUEST:
                matchingMethodNamesAndRespond(request, responseObserver);
                return;
            case METHOD_SIGNATURES_REQUEST:
                methodSignaturesAndRespond(request, responseObserver);
                return;
            case REWEAVE_REQUEST:
                reweaveAndRespond(request, responseObserver);
                return;
            case HEADER_REQUEST:
                getHeaderAndRespond(request, responseObserver);
                return;
            case ENTRIES_REQUEST:
                getEntriesAndRespond(request, responseObserver);
                return;
            case MAIN_THREAD_PROFILE_REQUEST:
                getMainThreadProfileAndRespond(request, responseObserver);
                return;
            case AUX_THREAD_PROFILE_REQUEST:
                getAuxThreadProfileAndRespond(request, responseObserver);
                return;
            case FULL_TRACE_REQUEST:
                getFullTraceAndRespond(request, responseObserver);
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
            agentConfigUpdater.update(request.getAgentConfigUpdateRequest().getAgentConfig());
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

    private void jstackAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        String jstack;
        try {
            jstack = liveJvmService.getJstack("");
        } catch (UnavailableDueToRunningInJreException e) {
            logger.debug(e.getMessage(), e);
            responseObserver.onNext(ClientResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setJstackResponse(JstackResponse.newBuilder()
                            .setUnavailableDueToRunningInJre(true))
                    .build());
            return;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setJstackResponse(JstackResponse.newBuilder()
                        .setJstack(jstack))
                .build());
    }

    private void availableDiskSpaceAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        long availableDiskSpaceBytes;
        try {
            availableDiskSpaceBytes = liveJvmService.getAvailableDiskSpace("",
                    request.getAvailableDiskSpaceRequest().getDirectory());
        } catch (DirectoryDoesNotExistException e) {
            logger.debug(e.getMessage(), e);
            responseObserver.onNext(ClientResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setAvailableDiskSpaceResponse(AvailableDiskSpaceResponse.newBuilder()
                            .setDirectoryDoesNotExist(true))
                    .build());
            return;
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
        } catch (DirectoryDoesNotExistException e) {
            logger.debug(e.getMessage(), e);
            responseObserver.onNext(ClientResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setHeapDumpResponse(HeapDumpResponse.newBuilder()
                            .setDirectoryDoesNotExist(true))
                    .build());
            return;
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

    private void heapHistogramAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        HeapHistogram heapHistogram;
        try {
            heapHistogram = liveJvmService.heapHistogram("");
        } catch (UnavailableDueToRunningInJreException e) {
            logger.debug(e.getMessage(), e);
            responseObserver.onNext(ClientResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setHeapHistogramResponse(HeapHistogramResponse.newBuilder()
                            .setUnavailableDueToRunningInJre(true))
                    .build());
            return;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setHeapHistogramResponse(HeapHistogramResponse.newBuilder()
                        .setHeapHistogram(heapHistogram))
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
        MBeanDumpRequest req = request.getMbeanDumpRequest();
        MBeanDump mbeanDump;
        try {
            mbeanDump = liveJvmService.getMBeanDump("", req.getKind(), req.getObjectNameList());
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

    private void matchingMBeanObjectNamesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MatchingMBeanObjectNamesRequest req = request.getMatchingMbeanObjectNamesRequest();
        List<String> objectNames;
        try {
            objectNames = liveJvmService.getMatchingMBeanObjectNames("", req.getPartialObjectName(),
                    req.getLimit());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMatchingMbeanObjectNamesResponse(MatchingMBeanObjectNamesResponse.newBuilder()
                        .addAllObjectName(objectNames))
                .build());
    }

    private void mbeanMetaAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MBeanMetaRequest req = request.getMbeanMetaRequest();
        MBeanMeta mbeanMeta;
        try {
            mbeanMeta = liveJvmService.getMBeanMeta("", req.getObjectName());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMbeanMetaResponse(MBeanMetaResponse.newBuilder()
                        .setMbeanMeta(mbeanMeta))
                .build());
    }

    private void systemPropertiesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        Map<String, String> systemProperties;
        try {
            systemProperties = liveJvmService.getSystemProperties("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setSystemPropertiesResponse(SystemPropertiesResponse.newBuilder()
                        .putAllSystemProperties(systemProperties))
                .build());
    }

    private void capabilitiesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        Capabilities capabilities;
        try {
            capabilities = liveJvmService.getCapabilities("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setCapabilitiesResponse(CapabilitiesResponse.newBuilder()
                        .setCapabilities(capabilities))
                .build());
    }

    private void globalMetaAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        GlobalMeta globalMeta;
        try {
            globalMeta = liveWeavingService.getGlobalMeta("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setGlobalMetaResponse(GlobalMetaResponse.newBuilder()
                        .setGlobalMeta(globalMeta))
                .build());
    }

    private void preloadClasspathCacheAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        try {
            liveWeavingService.preloadClasspathCache("");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setPreloadClasspathCacheResponse(
                        PreloadClasspathCacheResponse.getDefaultInstance())
                .build());
    }

    private void matchingClassNamesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MatchingClassNamesRequest req = request.getMatchingClassNamesRequest();
        List<String> classNames;
        try {
            classNames = liveWeavingService.getMatchingClassNames("", req.getPartialClassName(),
                    req.getLimit());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMatchingClassNamesResponse(MatchingClassNamesResponse.newBuilder()
                        .addAllClassName(classNames))
                .build());
    }

    private void matchingMethodNamesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MatchingMethodNamesRequest req = request.getMatchingMethodNamesRequest();
        List<String> methodNames;
        try {
            methodNames = liveWeavingService.getMatchingMethodNames("", req.getClassName(),
                    req.getPartialMethodName(), req.getLimit());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMatchingMethodNamesResponse(MatchingMethodNamesResponse.newBuilder()
                        .addAllMethodName(methodNames))
                .build());
    }

    private void methodSignaturesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) {
        MethodSignaturesRequest req = request.getMethodSignaturesRequest();
        List<MethodSignature> methodSignatures;
        try {
            methodSignatures = liveWeavingService.getMethodSignatures("", req.getClassName(),
                    req.getMethodName());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMethodSignaturesResponse(MethodSignaturesResponse.newBuilder()
                        .addAllMethodSignature(methodSignatures))
                .build());
    }

    private void reweaveAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        int classUpdateCount;
        try {
            classUpdateCount = liveWeavingService.reweave("");
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

    private void getHeaderAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        Trace.Header header;
        try {
            header = liveTraceRepository.getHeader("", request.getHeaderRequest().getTraceId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        HeaderResponse response;
        if (header == null) {
            response = HeaderResponse.getDefaultInstance();
        } else {
            response = HeaderResponse.newBuilder()
                    .setHeader(header)
                    .build();
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setHeaderResponse(response)
                .build());
    }

    private void getEntriesAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        Entries entries;
        try {
            entries = liveTraceRepository.getEntries("", request.getEntriesRequest().getTraceId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        EntriesResponse.Builder response = EntriesResponse.newBuilder();
        if (entries != null) {
            response.addAllEntry(entries.entries());
            response.addAllSharedQueryText(sharedQueryTextLimiter
                    .reduceTracePayloadWherePossible(entries.sharedQueryTexts()));
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setEntriesResponse(response)
                .build());
    }

    private void getMainThreadProfileAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        Profile profile;
        try {
            profile = liveTraceRepository.getMainThreadProfile("",
                    request.getMainThreadProfileRequest().getTraceId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        MainThreadProfileResponse response;
        if (profile == null) {
            response = MainThreadProfileResponse.getDefaultInstance();
        } else {
            response = MainThreadProfileResponse.newBuilder()
                    .setProfile(profile)
                    .build();
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setMainThreadProfileResponse(response)
                .build());
    }

    private void getAuxThreadProfileAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        Profile profile;
        try {
            profile = liveTraceRepository.getAuxThreadProfile("",
                    request.getAuxThreadProfileRequest().getTraceId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        AuxThreadProfileResponse response;
        if (profile == null) {
            response = AuxThreadProfileResponse.getDefaultInstance();
        } else {
            response = AuxThreadProfileResponse.newBuilder()
                    .setProfile(profile)
                    .build();
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setAuxThreadProfileResponse(response)
                .build());
    }

    private void getFullTraceAndRespond(ServerRequest request,
            StreamObserver<ClientResponse> responseObserver) throws Exception {
        Trace trace;
        try {
            trace = liveTraceRepository.getFullTrace("",
                    request.getFullTraceRequest().getTraceId());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            sendExceptionResponse(request, responseObserver);
            return;
        }
        FullTraceResponse response;
        if (trace == null) {
            response = FullTraceResponse.getDefaultInstance();
        } else {
            response = FullTraceResponse.newBuilder()
                    .setTrace(trace)
                    .build();
        }
        responseObserver.onNext(ClientResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setFullTraceResponse(response)
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
