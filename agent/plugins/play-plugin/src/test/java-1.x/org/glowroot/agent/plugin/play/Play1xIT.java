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
import java.lang.reflect.Constructor;
import java.net.ServerSocket;
import java.util.Iterator;
import java.util.List;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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

public class Play1xIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // javaagent is required for Executor.execute() weaving (tests run in play dev mode which
        // uses netty)
        container = Containers.createJavaagent();
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
        // when
        Trace trace = container.execute(GetIndex.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application#index");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("play action invoker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/index.html");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureApplicationIndexRoute() throws Exception {
        // when
        Trace trace = container.execute(GetApplicationIndex.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application#index");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("play action invoker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/index.html");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureApplicationCalculateRoute() throws Exception {
        // when
        Trace trace = container.execute(GetApplicationCalculate.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application#calculate");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("play action invoker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("play render: Application/calculate.html");

        assertThat(i.hasNext()).isFalse();
    }

    public static class GetIndex implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + PlayWrapper.port + "/");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationIndex implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet =
                    new HttpGet("http://localhost:" + PlayWrapper.port + "/application/index");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationCalculate implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet =
                    new HttpGet("http://localhost:" + PlayWrapper.port + "/application/calculate");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
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
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Play.init(new File("target/test-classes/application"), "test");
            Play.configuration.setProperty("http.port", Integer.toString(port));
            try {
                Constructor<Server> constructor = Server.class.getConstructor(String[].class);
                constructor.newInstance(new Object[] {new String[0]});
            } catch (Exception e) {
                try {
                    // play 1.1
                    Server.class.newInstance();
                } catch (Exception f) {
                    f.printStackTrace();
                    // re-throw original exception
                    throw new IllegalStateException(e);
                }
            }
            Play.start();
        }

        private static int getAvailablePort() throws Exception {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }
}
