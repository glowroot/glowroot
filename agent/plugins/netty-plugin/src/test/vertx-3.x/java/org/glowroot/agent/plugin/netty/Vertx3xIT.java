/*
 * Copyright 2016-2017 the original author or authors.
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

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class Vertx3xIT {

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
    public void shouldCaptureTransactionNameWithNormalServletMapping() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGet.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/abc");
        assertThat(trace.getHeader().getHeadline()).isEqualTo("GET /abc?xyz=123");
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
            HttpServer httpServer = Vertx.vertx().createHttpServer()
                    .requestHandler(req -> req.response().end("Hello World!"))
                    .listen(port);
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + port + "/abc?xyz=123");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            httpServer.close();
        }
    }
}
