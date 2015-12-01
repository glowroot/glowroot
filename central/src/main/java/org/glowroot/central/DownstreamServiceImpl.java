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
package org.glowroot.central;

import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.model.ConfigOuterClass.Config;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse.MessageCase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpRequest;

import static java.util.concurrent.TimeUnit.HOURS;

class DownstreamServiceImpl implements DownstreamService {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceImpl.class);

    private final Map<String, ConnectedAgent> connectedAgents = Maps.newConcurrentMap();

    @Override
    public StreamObserver<ClientResponse> connect(StreamObserver<ServerRequest> requestObserver) {
        ConnectedAgent connectedAgent = new ConnectedAgent(requestObserver);
        return connectedAgent.responseObserver;
    }

    void updateConfig(String serverId, Config config) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        connectedAgent.updateConfig(config);
    }

    int reweave(String serverId) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.reweave();
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

    MBeanDump mbeanDump(String serverId, MBeanDumpRequest request) throws Exception {
        ConnectedAgent connectedAgent = connectedAgents.get(serverId);
        if (connectedAgent == null) {
            throw new AgentNotConnectedException();
        }
        return connectedAgent.mbeanDump(request);
    }

    private class ConnectedAgent {

        private final AtomicLong nextRequestId = new AtomicLong();

        // expiration in the unlikely case that response is never returned from agent
        private final Cache<Long, ResponseHolder> responseHolders = CacheBuilder.newBuilder()
                .expireAfterWrite(1, HOURS)
                .build();

        private final StreamObserver<ClientResponse> responseObserver =
                new StreamObserver<ClientResponse>() {
                    @Override
                    public void onNext(ClientResponse value) {
                        if (value.getMessageCase() == MessageCase.HELLO) {
                            connectedAgents.put(value.getHello().getServerId(),
                                    ConnectedAgent.this);
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
                    public void onError(Throwable t) {}
                    @Override
                    public void onCompleted() {
                        requestObserver.onCompleted();
                    }
                };

        private volatile StreamObserver<ServerRequest> requestObserver;

        private ConnectedAgent(StreamObserver<ServerRequest> requestObserver) {
            this.requestObserver = requestObserver;
        }

        private void updateConfig(Config config) throws Exception {
            sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setConfigUpdateRequest(ConfigUpdateRequest.newBuilder()
                            .setConfig(config))
                    .build());
        }

        private int reweave() throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                    .build());
            return response.getReweaveResponse().getClassUpdateCount();
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

        private MBeanDump mbeanDump(MBeanDumpRequest request) throws Exception {
            ClientResponse response = sendRequest(ServerRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanDumpRequest(request)
                    .build());
            return response.getMbeanDumpResponse().getMbeanDump();
        }

        private ClientResponse sendRequest(ServerRequest request) throws Exception {
            while (requestObserver == null) {
                Thread.sleep(10);
            }
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
    public static class AgentNotConnectedException extends Exception {}

    @SuppressWarnings("serial")
    public static class AgentException extends Exception {}

    @SuppressWarnings("serial")
    public static class OutdatedAgentException extends Exception {}
}
