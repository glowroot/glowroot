/**
 * Copyright 2015-2016 the original author or authors.
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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class OkHttpClientPluginIT {

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
        Trace trace = container.execute(ExecuteHttpGet.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1/");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteHttpPost.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello2");
    }

    @Test
    public void shouldCaptureAsyncHttpGet() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpGet.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1/");
        assertThat(entries.get(1).getDepth()).isEqualTo(1);
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getDepth()).isEqualTo(2);
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
        assertThat(trace.getHeader().getAsyncTimer(0).getName())
                .isEqualTo("http client request");
    }

    @Test
    public void shouldCaptureAsyncHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteAsyncHttpPost.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello2");
        assertThat(entries.get(1).getDepth()).isEqualTo(1);
        assertThat(entries.get(1).getMessage()).matches("auxiliary thread");
        assertThat(entries.get(2).getDepth()).isEqualTo(2);
        assertThat(entries.get(2).getMessage()).matches("trace entry marker / CreateTraceEntry");
        assertThat(trace.getHeader().getAsyncTimer(0).getName())
                .isEqualTo("http client request");
    }

    public static class ExecuteHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://localhost:" + getPort() + "/hello1/")
                    .build();
            client.newCall(request).execute();
        }
    }

    public static class ExecuteHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            MediaType mediaType = MediaType.parse("text/plain; charset=utf-8");
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(mediaType, "hello");
            Request request = new Request.Builder()
                    .url("http://localhost:" + getPort() + "/hello2")
                    .post(body)
                    .build();
            client.newCall(request).execute();
        }
    }

    public static class ExecuteAsyncHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("http://localhost:" + getPort() + "/hello1/")
                    .build();
            final CountDownLatch latch = new CountDownLatch(1);
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(Response response) {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
                @Override
                public void onFailure(Request request, IOException e) {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            latch.await();
            // need to wait just a bit longer to ensure auxiliary thread capture completes
            Thread.sleep(100);
        }
    }

    public static class ExecuteAsyncHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            MediaType mediaType = MediaType.parse("text/plain; charset=utf-8");
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(mediaType, "hello");
            Request request = new Request.Builder()
                    .url("http://localhost:" + getPort() + "/hello2")
                    .post(body)
                    .build();
            final CountDownLatch latch = new CountDownLatch(1);
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(Response response) {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
                @Override
                public void onFailure(Request request, IOException e) {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            latch.await();
            // need to wait just a bit longer to ensure auxiliary thread capture completes
            Thread.sleep(100);
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
