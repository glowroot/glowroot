/**
 * Copyright 2015-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.httpclient;

import com.google.common.io.ByteStreams;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

public class ApacheHttpClient5xPluginIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureHttpGet() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGet.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpGetUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGetUsingHttpHostArg.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello2");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpPost.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello3");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpPostUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpPostUsingHttpHostArg.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello4");

        assertThat(i.hasNext()).isFalse();
    }

    private static CloseableHttpClient createHttpClient() throws Exception {
        return HttpClients.createDefault();
    }

    private static void closeHttpClient(CloseableHttpClient httpClient) throws Exception {
        httpClient.close(CloseMode.IMMEDIATE);
    }

    public static class ExecuteHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = createHttpClient();
            ClassicHttpRequest httpGet = ClassicRequestBuilder.get("http://localhost:" + getPort() + "/hello1").build();
            CloseableHttpResponse response = httpClient.execute(httpGet);
            int responseStatusCode = response.getCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
            InputStream content = response.getEntity().getContent();
            ByteStreams.exhaust(content);
            content.close();
            closeHttpClient(httpClient);
        }
    }

    public static class ExecuteHttpGetUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = createHttpClient();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            ClassicHttpRequest httpGet = ClassicRequestBuilder.get("/hello2").build();
            CloseableHttpResponse response = httpClient.execute(httpHost, httpGet);
            int responseStatusCode = response.getCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
            InputStream content = response.getEntity().getContent();
            ByteStreams.exhaust(content);
            content.close();
            closeHttpClient(httpClient);
        }
    }

    public static class ExecuteHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = createHttpClient();
            ClassicHttpRequest httpPost = ClassicRequestBuilder.post("http://localhost:" + getPort() + "/hello3").build();
            CloseableHttpResponse response = httpClient.execute(httpPost);
            int responseStatusCode = response.getCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
            InputStream content = response.getEntity().getContent();
            ByteStreams.exhaust(content);
            content.close();
            closeHttpClient(httpClient);
        }
    }

    public static class ExecuteHttpPostUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = createHttpClient();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            ClassicHttpRequest httpPost = ClassicRequestBuilder.post("/hello4").build();
            CloseableHttpResponse response = httpClient.execute(httpHost, httpPost);
            int responseStatusCode = response.getCode();
            if (responseStatusCode != 200) {
                throw new IllegalStateException(
                        "Unexpected response status code: " + responseStatusCode);
            }
            InputStream content = response.getEntity().getContent();
            ByteStreams.exhaust(content);
            content.close();
            closeHttpClient(httpClient);
        }
    }
}
