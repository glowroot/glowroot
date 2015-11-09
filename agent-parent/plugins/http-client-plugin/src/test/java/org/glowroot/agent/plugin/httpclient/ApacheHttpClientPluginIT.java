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
package org.glowroot.agent.plugin.httpclient;

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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ApacheHttpClientPluginIT {

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
        Trace trace = container.execute(ExecuteHttpGet.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("http client request: GET http://www.example.com/hello1");
    }

    @Test
    public void shouldCaptureHttpGetUsingHttpHostArg() throws Exception {
        Trace trace = container.execute(ExecuteHttpGetUsingHttpHostArg.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("http client request: GET http://www.example.com/hello2");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteHttpPost.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("http client request: POST http://www.example.com/hello3");
    }

    @Test
    public void shouldCaptureHttpPostUsingHttpHostArg() throws Exception {
        Trace trace = container.execute(ExecuteHttpPostUsingHttpHostArg.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("http client request: POST http://www.example.com/hello4");
    }

    public static class ExecuteHttpGet implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet("http://www.example.com/hello1");
            httpClient.execute(httpGet);
            httpClient.close();
        }
    }

    public static class ExecuteHttpGetUsingHttpHostArg implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpHost httpHost = new HttpHost("www.example.com");
            HttpGet httpGet = new HttpGet("/hello2");
            httpClient.execute(httpHost, httpGet);
            httpClient.close();
        }
    }

    public static class ExecuteHttpPost implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("http://www.example.com/hello3");
            httpClient.execute(httpPost);
            httpClient.close();
        }
    }

    public static class ExecuteHttpPostUsingHttpHostArg implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            CloseableHttpClient httpClient = HttpClients.createDefault();
            HttpHost httpHost = new HttpHost("www.example.com");
            HttpPost httpPost = new HttpPost("/hello4");
            httpClient.execute(httpHost, httpPost);
            httpClient.close();
        }
    }
}
