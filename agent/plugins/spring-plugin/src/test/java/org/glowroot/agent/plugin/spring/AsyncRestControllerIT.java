/*
 * Copyright 2019 the original author or authors.
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

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AsyncRestControllerIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        // javaagent is required for Executor.execute() weaving
        container = JavaagentContainer.create();
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
    public void shouldCaptureCallableAsyncController() throws Exception {
        shouldCaptureCallableAsyncController("", InvokeCallableAsyncController.class);
    }

    @Test
    public void shouldCaptureDeferredResultAsyncController() throws Exception {
        shouldCaptureDeferredResultAsyncController("", InvokeDeferredResultAsyncController.class);
    }

    @Test
    public void shouldCaptureCallableAsyncControllerWithContextPath() throws Exception {
        shouldCaptureCallableAsyncController("/zzz",
                InvokeCallableAsyncControllerWithContextPath.class);
    }

    @Test
    public void shouldCaptureDeferredResultAsyncControllerWithContextPath() throws Exception {
        shouldCaptureDeferredResultAsyncController("/zzz",
                InvokeDeferredResultAsyncControllerWithContextPath.class);
    }

    private void shouldCaptureCallableAsyncController(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getAsync()).isTrue();
        assertThat(trace.getHeader().getTransactionName()).isEqualTo(contextPath + "/rest-async");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller: org.glowroot.agent.plugin"
                + ".spring.AsyncRestControllerIT$CallableAsyncController.test()");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        if (i.hasNext()) {
            // this happens sporadically on travis ci because the auxiliary thread is demarcated by
            // WebAsyncManager$4.run(), which calls both CallableAsyncController$1.call() and
            // calls javax.servlet.AsyncContext.dispatch(), and sporadically dispatch() can process
            // and returns the response before WebAsyncManager$4.run() completes, leading to
            // glowroot adding a trace entry under the auxiliary thread to note that
            // "this auxiliary thread was still running when the transaction ended"

            // this is the stack trace for the call to CallableAsyncController$1.call():

            // org.glowroot.agent.plugin.spring.AsyncControllerIT$CallableAsyncController$1.call()
            // org.springframework.web.context.request.async.WebAsyncManager$4.run(WebAsyncManager.java:316)
            // java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:471)
            // java.util.concurrent.FutureTask.run(FutureTask.java:262)

            // this is the stack trace for the call to javax.servlet.AsyncContext.dispatch():

            // org.apache.catalina.core.AsyncContextImpl.dispatch(AsyncContextImpl.java)
            // org.springframework.web.context.request.async.StandardServletAsyncWebRequest.dispatch(StandardServletAsyncWebRequest.java:123)
            // org.springframework.web.context.request.async.WebAsyncManager.setConcurrentResultAndDispatch(WebAsyncManager.java:353)
            // org.springframework.web.context.request.async.WebAsyncManager.access$200(WebAsyncManager.java:58)
            // org.springframework.web.context.request.async.WebAsyncManager$4.run(WebAsyncManager.java:324)
            // java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:471)
            // java.util.concurrent.FutureTask.run(FutureTask.java:262)

            // see similar issue in org.glowroot.agent.plugin.servlet.AsyncServletIT

            entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(1);
            assertThat(entry.getMessage()).isEqualTo(
                    "this auxiliary thread was still running when the transaction ended");
        }

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureDeferredResultAsyncController(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getAsync()).isTrue();
        assertThat(trace.getHeader().getTransactionName()).isEqualTo(contextPath + "/rest-async2");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller: org.glowroot.agent.plugin"
                + ".spring.AsyncRestControllerIT$DeferredResultAsyncController.test()");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        if (i.hasNext()) {
            // this happens sporadically on travis ci because the auxiliary thread
            // (DeferredResultAsyncController$1) calls javax.servlet.AsyncContext.dispatch(), and
            // sporadically dispatch() can process and returns the response before
            // DeferredResultAsyncController$1.run() completes, leading to glowroot adding a trace
            // entry under the auxiliary thread to note that "this auxiliary thread was still
            // running when the transaction ended"

            // see similar issue in org.glowroot.agent.plugin.spring.AsyncControllerIT

            entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(2);
            assertThat(entry.getMessage()).isEqualTo(
                    "this auxiliary thread was still running when the transaction ended");
        }

        assertThat(i.hasNext()).isFalse();
    }

    public static class InvokeCallableAsyncController extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/rest-async");
        }
    }

    public static class InvokeDeferredResultAsyncController extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/rest-async2");
        }
    }

    public static class InvokeCallableAsyncControllerWithContextPath
            extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/rest-async");
        }
    }

    public static class InvokeDeferredResultAsyncControllerWithContextPath
            extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/rest-async2");
        }
    }

    @RestController
    public static class CallableAsyncController {

        @RequestMapping("rest-async")
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

    @RestController
    public static class DeferredResultAsyncController {

        @RequestMapping("rest-async2")
        public @ResponseBody DeferredResult<String> test() throws InterruptedException {
            new CreateTraceEntry().traceEntryMarker();
            final DeferredResult<String> result = new DeferredResult<String>();
            final ExecutorService executor = Executors.newCachedThreadPool();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    result.setResult("async2 world");
                    executor.shutdown();
                }
            });
            return result;
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
