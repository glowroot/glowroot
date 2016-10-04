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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.live.LiveJvmService.AgentUnsupportedOperationException;
import org.glowroot.common.live.LiveJvmService.DirectoryDoesNotExistException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInJreException;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.server.storage.AgentDao;
import org.glowroot.server.storage.AgentDao.AgentConfigUpdate;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceImplBase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AuxThreadProfileRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AuxThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CapabilitiesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.EntriesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.EntriesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.FullTraceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.FullTraceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeaderRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeaderResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HelloAck;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.JstackRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.JstackResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MainThreadProfileRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MainThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingClassNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMBeanObjectNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMethodNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignaturesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.PreloadClasspathCacheRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.SystemPropertiesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpRequest;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class DownstreamServiceImpl extends DownstreamServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceImpl.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final Map<String, ConnectedAgent> connectedAgents = Maps.newConcurrentMap();
    private final AgentDao agentDao;

    public DownstreamServiceImpl(AgentDao agentDao) {
        this.agentDao = agentDao;
    }

    @Override
    public StreamObserver<ClientResponse> connect(StreamObserver<ServerRequest> requestObserver) {
        return new ConnectedAgent(requestObserver);
    }

    public void updateAgentConfigIfConnectedAndNeeded(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent != null) {
            AgentConfigUpdate agentConfigUpdate = agentDao.readForAgentConfigUpdate(agentId);
            if (agentConfigUpdate != null) {
                connectedAgent.updateAgentConfig(agentConfigUpdate.config());
                agentDao.markAgentConfigUpdated(agentId, agentConfigUpdate.configUpdateToken());
            }
        }
    }

    boolean isAvailable(String agentId) {
        return connectedAgents.containsKey(agentId);
    }

    ThreadDump threadDump(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.threadDump();
    }

    String jstack(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.jstack();
    }

    long availableDiskSpaceBytes(String agentId, String directory) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.availableDiskSpaceBytes(directory);
    }

    HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.heapDump(directory);
    }

    HeapHistogram heapHistogram(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.heapHistogram();
    }

    void gc(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.gc();
    }

    MBeanDump mbeanDump(String agentId, MBeanDumpKind mbeanDumpKind, List<String> objectNames)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.mbeanDump(mbeanDumpKind, objectNames);
    }

    List<String> matchingMBeanObjectNames(String agentId, String partialObjectName, int limit)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingMBeanObjectNames(partialObjectName, limit);
    }

    MBeanMeta mbeanMeta(String agentId, String objectName) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.mbeanMeta(objectName);
    }

    Map<String, String> systemProperties(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.systemProperties();
    }

    Capabilities capabilities(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.capabilities();
    }

    GlobalMeta globalMeta(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.globalMeta();
    }

    void preloadClasspathCache(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.preloadClasspathCache();
    }

    List<String> matchingClassNames(String agentId, String partialClassName, int limit)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingClassNames(partialClassName, limit);
    }

    List<String> matchingMethodNames(String agentId, String className, String partialMethodName,
            int limit) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingMethodNames(className, partialMethodName, limit);
    }

    List<MethodSignature> methodSignatures(String agentId, String className, String methodName)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.methodSignatures(className, methodName);
    }

    int reweave(String agentId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.reweave();
    }

    @Nullable
    Trace.Header getHeader(String agentId, String traceId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.getHeader(traceId);
    }

    @Nullable
    Entries getEntries(String agentId, String traceId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.getEntries(traceId);
    }

    @Nullable
    Profile getMainThreadProfile(String agentId, String traceId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.getMainThreadProfile(traceId);
    }

    @Nullable
    Profile getAuxThreadProfile(String agentId, String traceId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.getAuxThreadProfile(traceId);
    }

    @Nullable
    Trace getFullTrace(String agentId, String traceId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(agentId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.getFullTrace(traceId);
    }

    private class ConnectedAgent implements StreamObserver<ClientResponse> {

        private final AtomicLong nextRequestId = new AtomicLong(1);

        // expiration in the unlikely case that response is never returned from agent
        private final Cache<Long, ResponseHolder> responseHolders = CacheBuilder.newBuilder()
                .expireAfterWrite(1, HOURS)
                .build();

        private volatile @MonotonicNonNull String agentId;

        private final StreamObserver<ServerRequest> requestObserver;

        private ConnectedAgent(StreamObserver<ServerRequest> requestObserver) {
            this.requestObserver = requestObserver;
        }

        @Override
        public void onNext(ClientResponse value) {
            if (value.getMessageCase() == MessageCase.HELLO) {
                agentId = value.getHello().getAgentId();
                connectedAgents.put(agentId, ConnectedAgent.this);
                synchronized (requestObserver) {
                    requestObserver.onNext(ServerRequest.newBuilder()
                            .setHelloAck(HelloAck.getDefaultInstance())
                            .build());
                }
                startupLogger.info("downstream connection (re-)established with agent: {}",
                        agentId);
                return;
            }
            long requestId = value.getRequestId();
            ResponseHolder responseHolder = responseHolders.getIfPresent(requestId);
            responseHolders.invalidate(requestId);
            if (responseHolder == null) {
                logger.error("no response holder for request id: {}", requestId);
                return;
            }
            try {
                // this shouldn't timeout since it is the other side of the exchange that is waiting
                responseHolder.response.exchange(value, 1, MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error(e.getMessage(), e);
            } catch (TimeoutException e) {
                logger.error(e.getMessage(), e);
            }
        }

        @Override
        public void onError(Throwable t) {
            logger.error(t.getMessage(), t);
            if (agentId != null) {
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        @Override
        public void onCompleted() {
            synchronized (requestObserver) {
                requestObserver.onCompleted();
            }
            if (agentId != null) {
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        private void updateAgentConfig(AgentConfig agentConfig) throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                            .setAgentConfig(agentConfig))
                    .build());
        }

        private ThreadDump threadDump() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setThreadDumpRequest(ThreadDumpRequest.getDefaultInstance())
                    .build());
            return responseWrapper.getThreadDumpResponse().getThreadDump();
        }

        private String jstack() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setJstackRequest(JstackRequest.getDefaultInstance())
                    .build());
            JstackResponse response = responseWrapper.getJstackResponse();
            if (response.getUnavailableDueToRunningInJre()) {
                throw new UnavailableDueToRunningInJreException();
            }
            return response.getJstack();
        }

        private long availableDiskSpaceBytes(String directory) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAvailableDiskSpaceRequest(AvailableDiskSpaceRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
            AvailableDiskSpaceResponse response = responseWrapper.getAvailableDiskSpaceResponse();
            if (response.getDirectoryDoesNotExist()) {
                throw new DirectoryDoesNotExistException();
            }
            return response.getAvailableBytes();
        }

        private HeapDumpFileInfo heapDump(String directory) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeapDumpRequest(HeapDumpRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
            HeapDumpResponse response = responseWrapper.getHeapDumpResponse();
            if (response.getDirectoryDoesNotExist()) {
                throw new DirectoryDoesNotExistException();
            }
            return response.getHeapDumpFileInfo();
        }

        private HeapHistogram heapHistogram() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeapHistogramRequest(HeapHistogramRequest.newBuilder())
                    .build());
            HeapHistogramResponse response = responseWrapper.getHeapHistogramResponse();
            if (response.getUnavailableDueToRunningInJre()) {
                throw new UnavailableDueToRunningInJreException();
            }
            return response.getHeapHistogram();
        }

        private void gc() throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGcRequest(GcRequest.getDefaultInstance())
                    .build());
        }

        private MBeanDump mbeanDump(MBeanDumpKind mbeanDumpKind, List<String> objectNames)
                throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanDumpRequest(MBeanDumpRequest.newBuilder()
                            .setKind(mbeanDumpKind)
                            .addAllObjectName(objectNames))
                    .build());
            return responseWrapper.getMbeanDumpResponse().getMbeanDump();
        }

        private List<String> matchingMBeanObjectNames(String partialObjectName, int limit)
                throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMbeanObjectNamesRequest(MatchingMBeanObjectNamesRequest.newBuilder()
                            .setPartialObjectName(partialObjectName)
                            .setLimit(limit))
                    .build());
            return responseWrapper.getMatchingMbeanObjectNamesResponse().getObjectNameList();
        }

        private MBeanMeta mbeanMeta(String objectName) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanMetaRequest(MBeanMetaRequest.newBuilder()
                            .setObjectName(objectName))
                    .build());
            return responseWrapper.getMbeanMetaResponse().getMbeanMeta();
        }

        private Map<String, String> systemProperties() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setSystemPropertiesRequest(SystemPropertiesRequest.getDefaultInstance())
                    .build());
            return responseWrapper.getSystemPropertiesResponse().getSystemPropertiesMap();
        }

        private Capabilities capabilities() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setCapabilitiesRequest(CapabilitiesRequest.getDefaultInstance())
                    .build());
            return responseWrapper.getCapabilitiesResponse().getCapabilities();
        }

        private GlobalMeta globalMeta() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGlobalMetaRequest(GlobalMetaRequest.getDefaultInstance())
                    .build());
            return responseWrapper.getGlobalMetaResponse().getGlobalMeta();
        }

        private void preloadClasspathCache() throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setPreloadClasspathCacheRequest(
                            PreloadClasspathCacheRequest.getDefaultInstance())
                    .build());
        }

        private List<String> matchingClassNames(String partialClassName, int limit)
                throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingClassNamesRequest(MatchingClassNamesRequest.newBuilder()
                            .setPartialClassName(partialClassName)
                            .setLimit(limit))
                    .build());
            return responseWrapper.getMatchingClassNamesResponse().getClassNameList();
        }

        private List<String> matchingMethodNames(String className, String partialMethodName,
                int limit) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMethodNamesRequest(MatchingMethodNamesRequest.newBuilder()
                            .setClassName(className)
                            .setPartialMethodName(partialMethodName)
                            .setLimit(limit))
                    .build());
            return responseWrapper.getMatchingMethodNamesResponse().getMethodNameList();
        }

        private List<MethodSignature> methodSignatures(String className, String methodName)
                throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMethodSignaturesRequest(MethodSignaturesRequest.newBuilder()
                            .setClassName(className)
                            .setMethodName(methodName))
                    .build());
            return responseWrapper.getMethodSignaturesResponse().getMethodSignatureList();
        }

        private int reweave() throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                    .build());
            return responseWrapper.getReweaveResponse().getClassUpdateCount();
        }

        private @Nullable Trace.Header getHeader(String traceId) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeaderRequest(HeaderRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
            HeaderResponse response = responseWrapper.getHeaderResponse();
            if (response.hasHeader()) {
                return response.getHeader();
            } else {
                return null;
            }
        }

        private @Nullable Entries getEntries(String traceId) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setEntriesRequest(EntriesRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
            EntriesResponse response = responseWrapper.getEntriesResponse();
            List<Trace.Entry> entries = response.getEntryList();
            if (entries.isEmpty()) {
                return null;
            }
            return ImmutableEntries.builder()
                    .addAllEntries(entries)
                    .addAllSharedQueryTexts(response.getSharedQueryTextList())
                    .build();
        }

        private @Nullable Profile getMainThreadProfile(String traceId) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMainThreadProfileRequest(MainThreadProfileRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
            MainThreadProfileResponse response = responseWrapper.getMainThreadProfileResponse();
            if (response.hasProfile()) {
                return response.getProfile();
            } else {
                return null;
            }
        }

        private @Nullable Profile getAuxThreadProfile(String traceId) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAuxThreadProfileRequest(AuxThreadProfileRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
            AuxThreadProfileResponse response = responseWrapper.getAuxThreadProfileResponse();
            if (response.hasProfile()) {
                return response.getProfile();
            } else {
                return null;
            }
        }

        private @Nullable Trace getFullTrace(String traceId) throws Exception {
            ClientResponse responseWrapper = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setFullTraceRequest(FullTraceRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
            FullTraceResponse response = responseWrapper.getFullTraceResponse();
            if (response.hasTrace()) {
                return response.getTrace();
            } else {
                return null;
            }
        }

        private ClientResponse sendRequest(ServerRequest request) throws Exception {
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(request.getRequestId(), responseHolder);
            // synchronization required since individual StreamObservers are not thread-safe
            synchronized (requestObserver) {
                requestObserver.onNext(request);
            }
            // timeout is in case agent never responds
            // passing ClientResponse.getDefaultInstance() is just dummy (non-null) value
            ClientResponse response = responseHolder.response
                    .exchange(ClientResponse.getDefaultInstance(), 1, MINUTES);
            if (response.getMessageCase() == MessageCase.UNKNOWN_REQUEST_RESPONSE) {
                throw new AgentUnsupportedOperationException();
            }
            if (response.getMessageCase() == MessageCase.EXCEPTION_RESPONSE) {
                throw new AgentException();
            }
            return response;
        }
    }

    private static class ResponseHolder {
        private final Exchanger<ClientResponse> response = new Exchanger<>();
    }

    @SuppressWarnings("serial")
    private static class AgentException extends Exception {}
}
