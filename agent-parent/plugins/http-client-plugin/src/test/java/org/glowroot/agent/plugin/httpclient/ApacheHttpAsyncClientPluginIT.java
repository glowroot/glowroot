/**
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
package org.glowroot.agent.plugin.httpclient;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ApacheHttpAsyncClientPluginIT {

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
    public void shouldCaptureAsyncHttpGet() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpGet.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1");
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureAsyncHttpGetUsingHttpHostArg() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpGetUsingHttpHostArg.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello2");
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureAsyncHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpPost.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello3");
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureAsyncHttpPostUsingHttpHostArg() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpPostUsingHttpHostArg.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello4");
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
    }

    public static class ExecuteAsyncHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpGet httpGet = new HttpGet("http://localhost:" + getPort() + "/hello1");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            httpClient.execute(httpGet, callback);
            callback.latch.await();
            httpClient.close();
        }
    }

    public static class ExecuteAsyncHttpGetUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            HttpGet httpGet = new HttpGet("/hello2");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            httpClient.execute(httpHost, httpGet, callback);
            callback.latch.await();
            httpClient.close();
        }
    }

    public static class ExecuteAsyncHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpPost httpPost = new HttpPost("http://localhost:" + getPort() + "/hello3");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            httpClient.execute(httpPost, callback);
            callback.latch.await();
            httpClient.close();
        }
    }

    public static class ExecuteAsyncHttpPostUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            HttpPost httpPost = new HttpPost("/hello4");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            httpClient.execute(httpHost, httpPost, callback);
            callback.latch.await();
            httpClient.close();
        }
    }

    private static class SimpleFutureCallback implements FutureCallback<HttpResponse> {

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void cancelled() {
            latch.countDown();
        }

        @Override
        public void completed(HttpResponse response) {
            new CreateTraceEntry().traceEntryMarker();
            latch.countDown();
        }

        @Override
        public void failed(Exception e) {
            latch.countDown();
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
