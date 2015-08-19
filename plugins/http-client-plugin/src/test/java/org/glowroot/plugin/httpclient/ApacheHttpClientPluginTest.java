/**
 * Copyright 2015 the original author or authors.
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
package org.glowroot.plugin.httpclient;

import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class ApacheHttpClientPluginTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
        container.executeAppUnderTest(ExecuteHttpGet.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessageText())
                .isEqualTo("http client request: GET http://www.example.com/hello1");
    }

    @Test
    public void shouldCaptureHttpGetUsingHttpHostArg() throws Exception {
        container.executeAppUnderTest(ExecuteHttpGetUsingHttpHostArg.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessageText())
                .isEqualTo("http client request: GET http://www.example.com/hello2");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        container.executeAppUnderTest(ExecuteHttpPost.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessageText())
                .isEqualTo("http client request: POST http://www.example.com/hello3");
    }

    @Test
    public void shouldCaptureHttpPostUsingHttpHostArg() throws Exception {
        container.executeAppUnderTest(ExecuteHttpPostUsingHttpHostArg.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessageText())
                .isEqualTo("http client request: POST http://www.example.com/hello4");
    }

    public static class ExecuteHttpGet implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://www.example.com/hello1");
            httpClient.execute(httpGet);
            httpClient.close();
        }
    }

    public static class ExecuteHttpGetUsingHttpHostArg implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpHost httpHost = new HttpHost("www.example.com");
            HttpGet httpGet = new HttpGet("/hello2");
            httpClient.execute(httpHost, httpGet);
            httpClient.close();
        }
    }

    public static class ExecuteHttpPost implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("http://www.example.com/hello3");
            httpClient.execute(httpPost);
            httpClient.close();
        }
    }

    public static class ExecuteHttpPostUsingHttpHostArg implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpHost httpHost = new HttpHost("www.example.com");
            HttpPost httpPost = new HttpPost("/hello4");
            httpClient.execute(httpHost, httpPost);
            httpClient.close();
        }
    }
}
