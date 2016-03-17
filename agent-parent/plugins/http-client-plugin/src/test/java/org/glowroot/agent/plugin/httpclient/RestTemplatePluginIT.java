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

import com.squareup.okhttp.*;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class RestTemplatePluginIT {

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
                .matches("http client resttemplate: GET http://localhost:\\d+/hello1/");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteHttpPost.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client resttemplate: POST http://localhost:\\d+/hello1/");
    }

    public static class ExecuteHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            RestTemplate rest = new RestTemplate();
            rest.getForObject("http://localhost:" + getPort() + "/hello1/", String.class);
        }
    }

    public static class ExecuteHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            RestTemplate rest = new RestTemplate();
            rest.postForObject("http://localhost:" + getPort() + "/hello1/", "Post!", String.class);
        }
    }

    private static class CreateTraceEntry implements TransactionMarker {

        @Override
        public void transactionMarker() {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
    }
}
