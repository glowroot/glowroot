/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.play;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import com.ning.http.client.AsyncHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import play.Play;
import play.server.Server;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class PlayIT {

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
    public void shouldCaptureIndexRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetIndex.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.index");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/index.html");
    }

    @Test
    public void shouldCaptureApplicationIndexRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetApplicationIndex.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.index");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/index.html");
    }

    @Test
    public void shouldCaptureApplicationCalculateRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetApplicationCalculate.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.calculate");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/calculate.html");
    }

    public static class GetIndex implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + PlayWrapper.port + "/")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationIndex implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + PlayWrapper.port + "/application/index")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationCalculate implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + PlayWrapper.port + "/application/calculate")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class PlayWrapper {

        protected static int port;

        static {
            try {
                port = getAvailablePort();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Play.init(new File("target/test-classes/application"), "test");
            new Server(new String[] {"--http.port=" + port});
            Play.configuration.setProperty("http.port", Integer.toString(port));
            Play.start();
        }

        private static int getAvailablePort() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }
}
