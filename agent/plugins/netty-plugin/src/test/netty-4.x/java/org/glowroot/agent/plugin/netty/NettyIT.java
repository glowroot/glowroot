/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.plugin.netty;

import java.net.ServerSocket;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class NettyIT {

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
    public void shouldCaptureHttpGet() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGet.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/abc");
        assertThat(trace.getHeader().getHeadline()).isEqualTo("GET /abc?xyz=123");
        assertThat(trace.getEntryList()).isEmpty();
    }

    @Ignore
    @Test
    public void shouldCaptureHttp2Get() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttp2Get.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/abc");
        assertThat(trace.getHeader().getHeadline()).isEqualTo("GET /abc?xyz=123");
        assertThat(trace.getEntryList()).isEmpty();
    }

    @Test
    public void shouldCaptureHttpChunkedResponse() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpChunked.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/chunked");
        assertThat(trace.getEntryList()).isEmpty();
    }

    @Test
    public void shouldCaptureHttpGetWithException() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGetWithException.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/exception");
        assertThat(trace.getHeader().getPartial()).isFalse();
        assertThat(trace.getEntryList()).isEmpty();
    }

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public static class ExecuteHttpGet implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            HttpServer server = new HttpServer(port);
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + port + "/abc?xyz=123");
            int code = httpClient.execute(httpGet).getCode();
            if (code != 200) {
                throw new IllegalStateException("Unexpected response code: " + code);
            }
            server.close();
        }
    }

    public static class ExecuteHttp2Get implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Http2Server server = new Http2Server(port);
            CloseableHttpAsyncClient httpClient = HttpAsyncClientBuilder.create()
                    .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                    .build();
            httpClient.start();
            SimpleHttpRequest httpGet =
                    new SimpleHttpRequest("GET", "http://localhost:" + port + "/hello1");
            Future<SimpleHttpResponse> future = httpClient.execute(httpGet, null);
            SimpleHttpResponse response = future.get();
            httpClient.close();
            int code = response.getCode();
            if (code != 200) {
                throw new IllegalStateException("Unexpected response code: " + code);
            }
            server.close();
        }
    }

    public static class ExecuteHttpChunked implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            HttpServer server = new HttpServer(port);
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + port + "/chunked");
            int code = httpClient.execute(httpGet).getCode();
            if (code != 200) {
                throw new IllegalStateException("Unexpected response code: " + code);
            }
            server.close();
        }
    }

    public static class ExecuteHttpGetWithException implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            HttpServer server = new HttpServer(port);
            CloseableHttpClient httpClient = HttpClientBuilder.create()
                    .disableAutomaticRetries()
                    .build();
            HttpGet httpGet = new HttpGet("http://localhost:" + port + "/exception");
            try {
                httpClient.execute(httpGet);
            } catch (NoHttpResponseException e) {
            }
            server.close();
        }
    }
}
