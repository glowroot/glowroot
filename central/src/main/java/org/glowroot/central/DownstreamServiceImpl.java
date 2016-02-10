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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CapabilitiesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HelloAck;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingClassNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMBeanObjectNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMethodNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignaturesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.PreloadClasspathCacheRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpRequest;

import static java.util.concurrent.TimeUnit.HOURS;

public class DownstreamServiceImpl implements DownstreamService {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceImpl.class);

    private final Map<String, ConnectedAgent> connectedAgents = Maps.newConcurrentMap();

    @Override
    public StreamObserver<ClientResponse> connect(StreamObserver<ServerRequest> requestObserver) {
        ConnectedAgent connectedAgent = new ConnectedAgent(requestObserver);
        return connectedAgent.responseObserver;
    }

    public void updateAgentConfig(String serverId, AgentConfig agentConfig) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.updateAgentConfig(agentConfig);
    }

    boolean isAvailable(String serverId) {
        return connectedAgents.containsKey(serverId);
    }

    ThreadDump threadDump(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.threadDump();
    }

    long availableDiskSpaceBytes(String serverId, String directory) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.availableDiskSpaceBytes(directory);
    }

    HeapDumpFileInfo heapDump(String serverId, String directory) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.heapDump(directory);
    }

    void gc(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.gc();
    }

    MBeanDump mbeanDump(String serverId, MBeanDumpKind mbeanDumpKind, List<String> objectNames)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.mbeanDump(mbeanDumpKind, objectNames);
    }

    List<String> matchingMBeanObjectNames(String serverId, String partialObjectName, int limit)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingMBeanObjectNames(partialObjectName, limit);
    }

    MBeanMeta mbeanMeta(String serverId, String objectName) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.mbeanMeta(objectName);
    }

    Capabilities capabilities(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.capabilities();
    }

    GlobalMeta globalMeta(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.globalMeta();
    }

    void preloadClasspathCache(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.preloadClasspathCache();
    }

    List<String> matchingClassNames(String serverId, String partialClassName, int limit)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingClassNames(partialClassName, limit);
    }

    List<String> matchingMethodNames(String serverId, String className, String partialMethodName,
            int limit) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.matchingMethodNames(className, partialMethodName, limit);
    }

    List<MethodSignature> methodSignatures(String serverId, String className, String methodName)
            throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.methodSignatures(className, methodName);
    }

    int reweave(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.reweave();
    }

    private class ConnectedAgent {

        private final AtomicLong nextRequestId = new AtomicLong();

        // expiration in the unlikely case that response is never returned from agent
        private final Cache<Long, ResponseHolder> responseHolders = CacheBuilder.newBuilder()
                .expireAfterWrite(1, HOURS)
                .build();

        private final StreamObserver<ClientResponse> responseObserver =
                new StreamObserver<ClientResponse>() {
                    private volatile @MonotonicNonNull String serverId;
                    @Override
                    public void onNext(ClientResponse value) {
                        if (value.getMessageCase() == MessageCase.HELLO) {
                            serverId = value.getHello().getServerId();
                            connectedAgents.put(serverId, ConnectedAgent.this);
                            requestObserver.onNext(ServerRequest.newBuilder()
                                    .setHelloAck(HelloAck.getDefaultInstance())
                                    .build());
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
                            responseHolder.response.exchange(value);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.error(e.getMessage(), e);
                        }
                    }
                    @Override
                    public void onError(Throwable t) {
                        logger.error(t.getMessage(), t);
                        if (serverId != null) {
                            connectedAgents.remove(serverId, ConnectedAgent.this);
                        }
                    }
                    @Override
                    public void onCompleted() {
                        requestObserver.onCompleted();
                        if (serverId != null) {
                            connectedAgents.remove(serverId, ConnectedAgent.this);
                        }
                    }
                };

        private final StreamObserver<ServerRequest> requestObserver;

        private ConnectedAgent(StreamObserver<ServerRequest> requestObserver) {
            this.requestObserver = requestObserver;
        }

        private void updateAgentConfig(AgentConfig agentConfig) throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                            .setAgentConfig(agentConfig))
                    .build());
        }

        private ThreadDump threadDump() throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setThreadDumpRequest(ThreadDumpRequest.getDefaultInstance())
                    .build());
            return response.getThreadDumpResponse().getThreadDump();
        }

        private long availableDiskSpaceBytes(String directory) throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAvailableDiskSpaceRequest(AvailableDiskSpaceRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
            return response.getAvailableDiskSpaceResponse().getAvailableBytes();
        }

        private HeapDumpFileInfo heapDump(String directory) throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeapDumpRequest(HeapDumpRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
            return response.getHeapDumpResponse().getHeapDumpFileInfo();
        }

        private void gc() throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGcRequest(GcRequest.getDefaultInstance())
                    .build());
        }

        private MBeanDump mbeanDump(MBeanDumpKind mbeanDumpKind, List<String> objectNames)
                throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanDumpRequest(MBeanDumpRequest.newBuilder()
                            .setKind(mbeanDumpKind)
                            .addAllObjectName(objectNames))
                    .build());
            return response.getMbeanDumpResponse().getMbeanDump();
        }

        private List<String> matchingMBeanObjectNames(String partialObjectName, int limit)
                throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMbeanObjectNamesRequest(MatchingMBeanObjectNamesRequest.newBuilder()
                            .setPartialObjectName(partialObjectName)
                            .setLimit(limit))
                    .build());
            return response.getMatchingMbeanObjectNamesResponse().getObjectNameList();
        }

        private MBeanMeta mbeanMeta(String objectName) throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanMetaRequest(MBeanMetaRequest.newBuilder()
                            .setObjectName(objectName))
                    .build());
            return response.getMbeanMetaResponse().getMbeanMeta();
        }

        private Capabilities capabilities() throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setCapabilitiesRequest(CapabilitiesRequest.getDefaultInstance())
                    .build());
            return response.getCapabilitiesResponse().getCapabilities();
        }

        private GlobalMeta globalMeta() throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGlobalMetaRequest(GlobalMetaRequest.getDefaultInstance())
                    .build());
            return response.getGlobalMetaResponse().getGlobalMeta();
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
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingClassNamesRequest(MatchingClassNamesRequest.newBuilder()
                            .setPartialClassName(partialClassName)
                            .setLimit(limit))
                    .build());
            return response.getMatchingClassNamesResponse().getClassNameList();
        }

        private List<String> matchingMethodNames(String className, String partialMethodName,
                int limit) throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMethodNamesRequest(MatchingMethodNamesRequest.newBuilder()
                            .setClassName(className)
                            .setPartialMethodName(partialMethodName)
                            .setLimit(limit))
                    .build());
            return response.getMatchingMethodNamesResponse().getMethodNameList();
        }

        private List<MethodSignature> methodSignatures(String className, String methodName)
                throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMethodSignaturesRequest(MethodSignaturesRequest.newBuilder()
                            .setClassName(className)
                            .setMethodName(methodName))
                    .build());
            return response.getMethodSignaturesResponse().getMethodSignatureList();
        }

        private int reweave() throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                    .build());
            return response.getReweaveResponse().getClassUpdateCount();
        }

        private ClientResponse sendRequest(ServerRequest request) throws Exception {
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(request.getRequestId(), responseHolder);
            requestObserver.onNext(request);
            // timeout is in case agent never responds
            ClientResponse response = responseHolder.response.exchange(null, 1, HOURS);
            if (response.getMessageCase() == MessageCase.UNKNOWN_REQUEST_RESPONSE) {
                throw new OutdatedAgentException();
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
    public static class AgentException extends Exception {}

    @SuppressWarnings("serial")
    public static class OutdatedAgentException extends Exception {}
}
