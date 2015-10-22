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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceStub;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanTreeResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;

import static java.util.concurrent.TimeUnit.SECONDS;

class CentralConnection {

    private static final Logger logger = LoggerFactory.getLogger(CentralConnection.class);

    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;
    private final StreamObserver<ClientResponse> responseObserver;

    CentralConnection(String server, String host, int port) throws Exception {

        // TODO reuse channel from GrpcCollector
        eventLoopGroup = EventLoopGroups.create("Glowroot-grpc-worker-ELG");
        executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-grpc-executor-%d")
                .build());
        channel = NettyChannelBuilder.forAddress(host, port)
                .eventLoopGroup(eventLoopGroup)
                .executor(executor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        DownstreamServiceStub asyncStub = DownstreamServiceGrpc.newStub(channel);

        RequestObserver requestObserver = new RequestObserver();
        responseObserver = asyncStub.connect(requestObserver);
        responseObserver.onNext(ClientResponse.newBuilder()
                .setHello(Hello.newBuilder().setServer(server))
                .build());
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        responseObserver.onCompleted();
        channel.shutdown();
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC channel");
        }
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC event loop group");
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
