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

import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceGrpc.CollectorService;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage;
import org.glowroot.wire.api.model.DownstreamServiceGrpc;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanTreeRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class CentralConnectionTest {

    private static final String SERVER_NAME = "abc xyz";

    private TestDownstreamService downstreamService;
    private Server server;
    private CentralConnection centralConnection;

    @Before
    public void beforeEach() throws Exception {
        downstreamService = new TestDownstreamService();
        server = NettyServerBuilder.forPort(8025)
                .addService(CollectorServiceGrpc.bindService(new TestCollectorService()))
                .addService(DownstreamServiceGrpc.bindService(downstreamService))
                .build()
                .start();
        centralConnection = new CentralConnection(SERVER_NAME, "localhost", 8025);
    }

    @After
    public void afterEach() throws InterruptedException {
        centralConnection.close();
        server.shutdown();
    }

    @Test
    public void test() throws Exception {
        // wait for client to connect
        Stopwatch stopwatch = Stopwatch.createStarted();
        StreamObserver<ServerRequest> requestObserver = null;
        while (stopwatch.elapsed(SECONDS) < 5) {
            requestObserver = downstreamService.clients.get(SERVER_NAME);
            if (requestObserver != null) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(requestObserver).isNotNull();
        requestObserver.onNext(ServerRequest.newBuilder()
                .setMbeanTreeRequest(MBeanTreeRequest.newBuilder()
                        .addIncludeAttrsForObjectName("abc/xyz")
                        .build())
                .build());
        Thread.sleep(100);
    }

    private static class TestCollectorService implements CollectorService {

        @Override
        public void collectAggregates(AggregateMessage aggregateMessage,
                StreamObserver<EmptyMessage> responseObserver) {
            responseObserver.onCompleted();
        }

        @Override
        public void collectGaugeValues(GaugeValueMessage gaugeValueMessage,
                StreamObserver<EmptyMessage> responseObserver) {
            responseObserver.onCompleted();
        }

        @Override
        public void collectTrace(TraceMessage traceMessage,
                StreamObserver<EmptyMessage> responseObserver) {
            responseObserver.onCompleted();
        }

        @Override
        public void getConfig(EmptyMessage emptyMessage,
                StreamObserver<ConfigMessage> responseObserver) {
            responseObserver.onNext(ConfigMessage.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    private static class TestDownstreamService implements DownstreamService {

        private final ConcurrentMap<String, StreamObserver<ServerRequest>> clients =
                Maps.newConcurrentMap();

        @Override
        public StreamObserver<ClientResponse> connect(
                StreamObserver<ServerRequest> requestObserver) {
            return new TestResponseObserver(requestObserver, clients);
        }
    }

    private static class TestResponseObserver implements StreamObserver<ClientResponse> {

        private final StreamObserver<ServerRequest> requestObserver;
        private final ConcurrentMap<String, StreamObserver<ServerRequest>> clients;

        private TestResponseObserver(StreamObserver<ServerRequest> requestObserver,
                ConcurrentMap<String, StreamObserver<ServerRequest>> clients) {
            this.requestObserver = requestObserver;
            this.clients = clients;
        }

        @Override
        public void onNext(ClientResponse value) {
            switch (value.getMessageCase()) {
                case HELLO:
                    String server = value.getHello().getServer();
                    if (server.isEmpty()) {
                        throw new IllegalStateException();
                    }
                    clients.put(server, requestObserver);
                    break;
                case MBEAN_TREE_RESPONSE:
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
        }

        @Override
        public void onError(Throwable t) {
            throw new RuntimeException(t);
        }

        @Override
        public void onCompleted() {
            requestObserver.onCompleted();
        }
    }
}
