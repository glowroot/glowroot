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
package org.glowroot.agent.central;

import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.model.DownstreamServiceGrpc;
import org.glowroot.collector.spi.model.DownstreamServiceGrpc.DownstreamServiceStub;
import org.glowroot.collector.spi.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.collector.spi.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.collector.spi.model.DownstreamServiceOuterClass.MBeanTreeResponse;
import org.glowroot.collector.spi.model.DownstreamServiceOuterClass.ServerRequest;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.SECONDS;

public class CentralConnection {

    private static final Logger logger = LoggerFactory.getLogger(CentralConnection.class);

    private final ManagedChannel channel;
    private final StreamObserver<ClientResponse> responseObserver;

    public CentralConnection(long serverId, String host, int port) throws Exception {

        channel = NettyChannelBuilder.forAddress(host, port)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        DownstreamServiceStub asyncStub = DownstreamServiceGrpc.newStub(channel);

        RequestObserver requestObserver = new RequestObserver();
        responseObserver = asyncStub.connect(requestObserver);
        responseObserver.onNext(ClientResponse.newBuilder()
                .setHello(Hello.newBuilder().setServerId(serverId))
                .build());
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        responseObserver.onCompleted();
        channel.shutdownNow();
        boolean terminated = channel.awaitTermination(5, SECONDS);
        if (!terminated) {
            throw new RuntimeException("GRPC channel did not terminate");
        }
    }

    private class RequestObserver implements StreamObserver<ServerRequest> {

        @Override
        public void onNext(ServerRequest request) {
            switch (request.getMessageCase()) {
                case MBEAN_TREE_REQUEST:
                    responseObserver.onNext(ClientResponse.newBuilder()
                            .setRequestId(request.getRequestId())
                            .setMbeanTreeResponse(MBeanTreeResponse.getDefaultInstance())
                            .build());
                    break;
                default:
                    // a newer request type that this version of the agent doesn't understand
                    responseObserver.onNext(ClientResponse.newBuilder()
                            .setRequestId(request.getRequestId())
                            .build());
                    break;
            }
        }

        @Override
        public void onError(Throwable t) {
            logger.error(t.getMessage(), t);
        }

        @Override
        public void onCompleted() {
            // this should not happen
            logger.error("the server initiated stream termination");
        }
    }
}
