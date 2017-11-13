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
package org.glowroot.agent.plugin.play;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import play.test.TestServer;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class Play2xIT {

    private static final boolean PLAY_2_0_X = Boolean.getBoolean("glowroot.test.play20x");

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // javaagent is required for Executor.execute() weaving
        // -Dlogger.resource is needed to configure play logging (at least on 2.0.8)
        container = JavaagentContainer
                .createWithExtraJvmArgs(ImmutableList.of("-Dlogger.resource=logback-test.xml"));
        // need warmup to avoid capturing rendering of views.html.defaultpages.todo during
        // play.mvc.Results static initializer (at least on play 2.3.10)
        container.execute(GetIndex.class);
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
        if (PLAY_2_0_X) {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("HomeController#index");
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("/");
        }
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("play render: index");

        if (i.hasNext()) {
            entry = i.next();
            throw new AssertionError("Unexpected entry: depth=" + entry.getDepth() + ", message="
                    + entry.getMessage());
        }

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureIndexRouteUsingAltTransactionNaming() throws Exception {
        // given
        container.getConfigService().setPluginProperty("play", "useAltTransactionNaming", true);

        // when
        Trace trace = container.execute(GetIndex.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HomeController#index");
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("play render: index");

        if (i.hasNext()) {
            entry = i.next();
            throw new AssertionError("Unexpected entry: depth=" + entry.getDepth() + ", message="
                    + entry.getMessage());
        }

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAsyncRoute() throws Exception {
        // when
        Trace trace = container.execute(GetAsync.class);

        // then
        if (PLAY_2_0_X) {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("AsyncController#message");
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("/message");
        }
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        if (i.hasNext()) {
            entry = i.next();
            throw new AssertionError("Unexpected entry: depth=" + entry.getDepth() + ", message="
                    + entry.getMessage());
        }

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureStreamRoute() throws Exception {
        // when
        Trace trace = container.execute(GetStream.class);

        // then
        if (PLAY_2_0_X) {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("StreamController#stream");
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("/stream");
        }
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        if (i.hasNext()) {
            entry = i.next();
            throw new AssertionError("Unexpected entry: depth=" + entry.getDepth() + ", message="
                    + entry.getMessage());
        }

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAssetRoute() throws Exception {
        // when
        Trace trace = container.execute(GetAsset.class);

        // then
        if (PLAY_2_0_X) {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("Assets#at");
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("/assets/**");
        }
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).isEmpty();
        assertThat(trace.getHeader().hasError()).isFalse();
    }

    @Test
    public void shouldCaptureError() throws Exception {
        // when
        Trace trace = container.execute(GetBad.class);

        // then
        if (PLAY_2_0_X) {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("BadController#bad");
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("/bad");
        }
        assertThat(trace.getHeader().getError().getMessage()).contains("Internal server error");
        Proto.Throwable throwable = trace.getHeader().getError().getException();
        while (throwable.hasCause()) {
            throwable = throwable.getCause();
        }
        assertThat(throwable.getClassName()).isEqualTo("java.lang.RuntimeException");
        assertThat(throwable.getMessage()).isEqualTo("Bad");
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

    public static class GetAsset implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet(
                    "http://localhost:" + PlayWrapper.port + "/assets/scripts/empty.js");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetAsync implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + PlayWrapper.port + "/message");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetBad implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + PlayWrapper.port + "/bad");
            int statusCode = httpClient.execute(httpGet).getStatusLine().getStatusCode();
            if (statusCode != 500) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetStream implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://localhost:" + PlayWrapper.port + "/stream");
            CloseableHttpResponse response = httpClient.execute(httpGet);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            int len = ByteStreams.toByteArray(response.getEntity().getContent()).length;
            if (len != 10) {
                throw new IllegalStateException("Unexpected content length: " + len);
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

            TestServer server;
            try {
                server = createNewerPlayServer();
            } catch (Exception e) {
                try {
                    server = createOlderPlayServer();
                } catch (Exception f) {
                    try {
                        server = createEvenOlderPlayServer();
                    } catch (Exception g) {
                        // throw original exception
                        throw new RuntimeException(e);
                    }
                }
            }
            server.start();
        }

        private static TestServer createNewerPlayServer() throws Exception {
            Class<?> environmentClass = Class.forName("play.Environment");

            Class<?> builderClass = Class.forName("play.inject.guice.GuiceApplicationBuilder");
            Method inMethod = builderClass.getMethod("in", environmentClass);
            Method buildMethod = builderClass.getMethod("build");

            Object env = environmentClass.getConstructor(File.class).newInstance(new File("."));
            Object builder = builderClass.newInstance();
            builder = inMethod.invoke(builder, env);
            Object app = buildMethod.invoke(builder);

            Class<?> applicationClass = Class.forName("play.Application");
            Constructor<TestServer> testServerConstructor =
                    TestServer.class.getConstructor(int.class, applicationClass);
            return testServerConstructor.newInstance(port, app);
        }

        private static TestServer createOlderPlayServer() throws Exception {
            Class<?> globalSettingsClass = Class.forName("play.GlobalSettings");
            Class<?> fakeApplicationClass = Class.forName("play.test.FakeApplication");
            Constructor<?> fakeApplicationConstructor = fakeApplicationClass.getConstructor(
                    File.class, ClassLoader.class, Map.class, List.class, globalSettingsClass);
            Object app = fakeApplicationConstructor.newInstance(new File("."),
                    PlayWrapper.class.getClassLoader(), ImmutableMap.of(), ImmutableList.of(),
                    null);
            Constructor<TestServer> testServerConstructor =
                    TestServer.class.getConstructor(int.class, fakeApplicationClass);
            return testServerConstructor.newInstance(port, app);
        }

        // play 2.0.x
        private static TestServer createEvenOlderPlayServer() throws Exception {
            Class<?> fakeApplicationClass = Class.forName("play.test.FakeApplication");
            Constructor<?> fakeApplicationConstructor = fakeApplicationClass.getConstructor(
                    File.class, ClassLoader.class, Map.class, List.class);
            Object app = fakeApplicationConstructor.newInstance(new File("."),
                    PlayWrapper.class.getClassLoader(), ImmutableMap.of(), ImmutableList.of());
            Constructor<TestServer> testServerConstructor =
                    TestServer.class.getConstructor(int.class, fakeApplicationClass);
            return testServerConstructor.newInstance(port, app);
        }

        private static int getAvailablePort() throws Exception {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }
}
