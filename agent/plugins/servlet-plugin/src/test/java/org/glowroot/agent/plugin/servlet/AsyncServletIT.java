/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.AsyncContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AsyncServletIT {

    private static final String PLUGIN_ID = "servlet";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // async servlet test relies on executor plugin, which only works under javaagent
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
    public void testAsyncServlet() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(InvokeAsync.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo("/async");
        assertThat(header.getTransactionName()).isEqualTo("/async");
        assertThat(header.getEntryCount()).isEqualTo(3);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(1).getDepth()).isEqualTo(0);
        assertThat(entries.get(1).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(2).getDepth()).isEqualTo(1);
        assertThat(entries.get(2).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        // and check session attributes set across async boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");
    }

    @Test
    public void testAsyncServlet2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(InvokeAsync2.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo("/async2");
        assertThat(header.getTransactionName()).isEqualTo("/async2");
        assertThat(header.getEntryCount()).isEqualTo(3);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(1).getDepth()).isEqualTo(0);
        assertThat(entries.get(1).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(2).getDepth()).isEqualTo(1);
        assertThat(entries.get(2).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        // and check session attributes set across async boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");
    }

    @Test
    public void testAsyncServletWithDispatch() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(InvokeAsyncWithDispatch.class);
        Thread.sleep(1000);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo("/async3");
        assertThat(header.getTransactionName()).isEqualTo("/async3");
        assertThat(header.getEntryCount()).isEqualTo(5);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(1).getDepth()).isEqualTo(0);
        assertThat(entries.get(1).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(2).getDepth()).isEqualTo(1);
        assertThat(entries.get(2).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        assertThat(entries.get(3).getDepth()).isEqualTo(1);
        assertThat(entries.get(3).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(4).getDepth()).isEqualTo(2);
        assertThat(entries.get(4).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        // and check session attributes set across async and dispatch boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async-dispatch"))
                .isEqualTo("c");
    }

    public static class InvokeAsync extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/async")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeAsync2 extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/async2")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeAsyncWithDispatch extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            // send initial to trigger servlet init methods so they don't end up in trace
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/async3")
                            .execute().get().getStatusCode();
            if (statusCode != 200) {
                asyncHttpClient.close();
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/async3")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    @WebServlet(value = "/async", asyncSupported = true)
    @SuppressWarnings("serial")
    public static class AsyncServlet extends HttpServlet {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void destroy() {
            executor.shutdown();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("sync", "a");
            new CreateTraceEntry().traceEntryMarker();
            final AsyncContext asyncContext = request.startAsync();
            asyncContext.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                        ((HttpServletRequest) asyncContext.getRequest()).getSession()
                                .setAttribute("async", "b");
                        new CreateTraceEntry().traceEntryMarker();
                        asyncContext.getResponse().getWriter().println("async response");
                        asyncContext.complete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    @WebServlet(value = "/async2", asyncSupported = true)
    @SuppressWarnings("serial")
    public static class AsyncServlet2 extends HttpServlet {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void destroy() {
            executor.shutdown();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("sync", "a");
            new CreateTraceEntry().traceEntryMarker();
            final AsyncContext asyncContext = request.startAsync(request, response);
            asyncContext.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                        ((HttpServletRequest) asyncContext.getRequest()).getSession()
                                .setAttribute("async", "b");
                        new CreateTraceEntry().traceEntryMarker();
                        asyncContext.getResponse().getWriter().println("async response");
                        asyncContext.complete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    @WebServlet(value = "/async3", asyncSupported = true)
    @SuppressWarnings("serial")
    public static class AsyncServletWithDispatch extends HttpServlet {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void destroy() {
            executor.shutdown();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("sync", "a");
            new CreateTraceEntry().traceEntryMarker();
            final AsyncContext asyncContext = request.startAsync();
            asyncContext.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                        ((HttpServletRequest) asyncContext.getRequest()).getSession()
                                .setAttribute("async", "b");
                        new CreateTraceEntry().traceEntryMarker();
                        asyncContext.dispatch("/async-forward");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    @WebServlet(value = "/async-forward")
    @SuppressWarnings("serial")
    public static class SimpleServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            request.getSession().setAttribute("async-dispatch", "c");
            new CreateTraceEntry().traceEntryMarker();
            response.getWriter().println("the response");
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
