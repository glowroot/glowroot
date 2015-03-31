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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
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

public class AsyncHttpClientPluginTest {

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
        assertThat(entries.get(0).getMessage().getText()).isEqualTo(
                "http client request: GET http://www.example.com/hello1");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        container.executeAppUnderTest(ExecuteHttpPost.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage().getText()).isEqualTo(
                "http client request: POST http://www.example.com/hello2");
    }

    public static class ExecuteHttpGet implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            BoundRequestBuilder x = asyncHttpClient.prepareGet("http://www.example.com/hello1");
            x.execute();
            asyncHttpClient.close();
        }
    }

    public static class ExecuteHttpPost implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.preparePost("http://www.example.com/hello2").execute();
            asyncHttpClient.close();
        }
    }
}
