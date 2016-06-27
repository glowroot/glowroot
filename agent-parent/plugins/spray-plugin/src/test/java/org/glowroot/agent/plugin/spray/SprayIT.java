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
package org.glowroot.agent.plugin.spray;

import java.lang.reflect.Method;
import java.net.ServerSocket;

import com.google.common.base.Stopwatch;
import com.ning.http.client.AsyncHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class SprayIT {

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
        // given
        // when
        Trace trace = container.execute(ExecuteHttpGet.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/abc");
        assertThat(trace.getHeader().getHeadline()).isEqualTo("GET /abc?xyz=123");
        assertThat(trace.getEntryCount()).isZero();
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
            String port = Integer.toString(getAvailablePort());
            Method method = Class.forName("example.Boot").getMethod("main", String[].class);
            method.invoke(null, (Object) new String[] {port});
            int statusCode = executeMaybeWaitingForStartup(port);
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }

        private int executeMaybeWaitingForStartup(String port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            try {
                Exception lastException = null;
                Stopwatch stopwatch = Stopwatch.createStarted();
                while (stopwatch.elapsed(SECONDS) < 10) {
                    try {
                        return asyncHttpClient
                                .prepareGet("http://localhost:" + port + "/abc?xyz=123").execute()
                                .get().getStatusCode();
                    } catch (Exception e) {
                        lastException = e;
                    }
                    Thread.sleep(100);
                }
                throw lastException;
            } finally {
                asyncHttpClient.close();
            }
        }
    }
}
