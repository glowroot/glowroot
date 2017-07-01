/**
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
package org.glowroot.agent.plugin.httpclient;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

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
        // when
        Trace trace = container.execute(ExecuteAsyncHttpGet.class);

        // then
        assertThat(trace.getHeader().getAsyncTimer(0).getActive()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).matches("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).matches("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAsyncHttpGetUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteAsyncHttpGetUsingHttpHostArg.class);

        // then
        assertThat(trace.getHeader().getAsyncTimer(0).getActive()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello2");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).matches("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).matches("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAsyncHttpPost() throws Exception {
        // when
        Trace trace = container.execute(ExecuteAsyncHttpPost.class);

        // then
        assertThat(trace.getHeader().getAsyncTimer(0).getActive()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello3");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).matches("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).matches("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAsyncHttpPostUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteAsyncHttpPostUsingHttpHostArg.class);

        // then
        assertThat(trace.getHeader().getAsyncTimer(0).getActive()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello4");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).matches("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).matches("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ExecuteAsyncHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpGet httpGet = new HttpGet("http://localhost:" + getPort() + "/hello1");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            Future<HttpResponse> future = httpClient.execute(httpGet, callback);
            callback.latch.await();
            httpClient.close();
            int responseStatusCode = future.get().getStatusLine().getStatusCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
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
            Future<HttpResponse> future = httpClient.execute(httpHost, httpGet, callback);
            callback.latch.await();
            httpClient.close();
            int responseStatusCode = future.get().getStatusLine().getStatusCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
        }
    }

    public static class ExecuteAsyncHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpAsyncClient httpClient = HttpAsyncClients.createDefault();
            httpClient.start();
            HttpPost httpPost = new HttpPost("http://localhost:" + getPort() + "/hello3");
            SimpleFutureCallback callback = new SimpleFutureCallback();
            Future<HttpResponse> future = httpClient.execute(httpPost, callback);
            callback.latch.await();
            httpClient.close();
            int responseStatusCode = future.get().getStatusLine().getStatusCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
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
            Future<HttpResponse> future = httpClient.execute(httpHost, httpPost, callback);
            callback.latch.await();
            httpClient.close();
            int responseStatusCode = future.get().getStatusLine().getStatusCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
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
