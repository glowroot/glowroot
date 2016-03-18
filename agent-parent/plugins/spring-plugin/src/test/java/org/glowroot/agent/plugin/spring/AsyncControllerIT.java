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
package org.glowroot.agent.plugin.spring;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.DeferredResult;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AsyncControllerIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // javaagent is required for Executor.execute() weaving
        container = Containers.createJavaagent();
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
    public void shouldCaptureCallableAsyncController() throws Exception {
        // given
        // when
        Trace trace = container.execute(CallableAsyncServlet.class);
        // then
        assertThat(trace.getHeader().getAsync()).isTrue();
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/async");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.AsyncControllerIT"
                + "$CallableAsyncController.test()");
        assertThat(entries.get(1).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(2).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(3).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureDeferredResultAsyncController() throws Exception {
        // given
        // when
        Trace trace = container.execute(DeferredResultAsyncServlet.class);
        // then
        assertThat(trace.getHeader().getAsync()).isTrue();
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/async2");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.AsyncControllerIT"
                + "$DeferredResultAsyncController.test()");
        assertThat(entries.get(1).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(2).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(3).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
    }

    public static class CallableAsyncServlet extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/async");
        }
    }

    public static class DeferredResultAsyncServlet extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/async2");
        }
    }

    @Controller
    public static class CallableAsyncController {

        @RequestMapping(value = "async")
        public @ResponseBody Callable<String> test() throws InterruptedException {
            new CreateTraceEntry().traceEntryMarker();
            return new Callable<String>() {
                @Override
                public String call() throws Exception {
                    new CreateTraceEntry().traceEntryMarker();
                    return "async world";
                }
            };
        }
    }

    @Controller
    public static class DeferredResultAsyncController {

        @RequestMapping(value = "async2")
        public @ResponseBody DeferredResult<String> test() throws InterruptedException {
            new CreateTraceEntry().traceEntryMarker();
            final DeferredResult<String> result = new DeferredResult<String>();
            ExecutorService executor = Executors.newCachedThreadPool();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    result.setResult("async2 world");
                }
            });
            executor.shutdown();
            return result;
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
