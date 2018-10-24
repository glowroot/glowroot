/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.spring;

import java.net.ServerSocket;
import java.util.Iterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class WebFluxIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCapture() throws Exception {
        // when
        Trace trace = container.execute(HittingWebFlux.class);

        // then
        assertThat(trace.getHeader().getHeadline()).isEqualTo("GET /webflux/abc");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).matches("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public static class HittingWebFlux implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            DisposableServer httpServer = HttpServer.create()
                    .host("localhost")
                    .port(port)
                    .handle(new ReactorHttpHandlerAdapter(new MyHttpHandler()))
                    .bind()
                    .block();

            WebClient client = WebClient.create("http://localhost:" + port);
            client.get()
                    .uri("/webflux/abc")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            httpServer.dispose();
        }
    }

    private static class MyHttpHandler implements HttpHandler {
        @Override
        public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
            new CreateTraceEntry().traceEntryMarker();
            HttpClient httpClient = HttpClient.create();
            return httpClient.baseUrl("http://example.org")
                    .get()
                    .response()
                    .doOnError(t -> {
                        t.printStackTrace();
                    }).doOnNext(res -> {
                        response.writeWith(
                                Mono.just(response.bufferFactory().wrap("xyzo".getBytes())));
                    }).then();
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
