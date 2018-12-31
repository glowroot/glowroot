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
package org.glowroot.agent.plugin.netty;

import java.net.ServerSocket;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
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

public class Http2IT {

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

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    public static class ExecuteHttp2Get implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Http2Server server = new Http2Server(port, false);
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
}
