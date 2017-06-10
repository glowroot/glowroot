/*
 * Copyright 2011-2017 the original author or authors.
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
import java.util.Iterator;
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

import org.glowroot.agent.it.harness.AppUnderTest;
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
        testAsyncServlet("", InvokeAsync.class);
    }

    @Test
    public void testAsyncServletWithContextPath() throws Exception {
        testAsyncServlet("/zzz", InvokeAsyncWithContextPath.class);
    }

    @Test
    public void testAsyncServlet2() throws Exception {
        testAsyncServlet2("", InvokeAsync2.class);
    }

    @Test
    public void testAsyncServlet2WithContextPath() throws Exception {
        testAsyncServlet2("/zzz", InvokeAsync2WithContextPath.class);
    }

    @Test
    public void testAsyncServletWithDispatch() throws Exception {
        testAsyncServletWithDispatch("", InvokeAsyncWithDispatch.class);
    }

    @Test
    public void testAsyncServletWithDispatchWithContextPath() throws Exception {
        testAsyncServletWithDispatch("/zzz", InvokeAsyncWithDispatchWithContextPath.class);
    }

    private void testAsyncServlet(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");

        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/async");
        assertThat(header.getTransactionName()).isEqualTo(contextPath + "/async");

        // check session attributes set across async boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    private void testAsyncServlet2(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");

        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/async2");
        assertThat(header.getTransactionName()).isEqualTo(contextPath + "/async2");

        // check session attributes set across async boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    private void testAsyncServletWithDispatch(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");

        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAsync()).isTrue();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/async3");
        assertThat(header.getTransactionName()).isEqualTo(contextPath + "/async3");

        // and check session attributes set across async and dispatch boundary
        assertThat(SessionAttributeIT.getSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getInitialSessionAttributes(trace)).isNull();
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("sync"))
                .isEqualTo("a");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async"))
                .isEqualTo("b");
        assertThat(SessionAttributeIT.getUpdatedSessionAttributes(trace).get("async-dispatch"))
                .isEqualTo("c");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

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
            // (AsyncServletWithDispatch$1) calls javax.servlet.AsyncContext.dispatch(), and
            // sporadically dispatch() can process and returns the response before
            // AsyncServletWithDispatch$1.run() completes, leading to glowroot adding a trace entry
            // under the auxiliary thread to note that "this auxiliary thread was still running when
            // the transaction ended"

            // see similar issue in org.glowroot.agent.plugin.spring.AsyncControllerIT

            entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(1);
            assertThat(entry.getMessage()).isEqualTo(
                    "this auxiliary thread was still running when the transaction ended");
        }

        assertThat(i.hasNext()).isFalse();
    }

    public static class InvokeAsync extends InvokeAsyncBase {
        public InvokeAsync() {
            super("");
        }
    }

    public static class InvokeAsyncWithContextPath extends InvokeAsyncBase {
        public InvokeAsyncWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeAsyncBase extends InvokeServletInTomcat {

        private InvokeAsyncBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + contextPath + "/async")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeAsync2 extends InvokeAsync2Base {
        public InvokeAsync2() {
            super("");
        }
    }

    public static class InvokeAsync2WithContextPath extends InvokeAsync2Base {
        public InvokeAsync2WithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeAsync2Base extends InvokeServletInTomcat {

        private InvokeAsync2Base(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + contextPath + "/async2")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeAsyncWithDispatch extends InvokeAsyncWithDispatchBase {
        public InvokeAsyncWithDispatch() {
            super("");
        }
    }

    public static class InvokeAsyncWithDispatchWithContextPath extends InvokeAsyncWithDispatchBase {
        public InvokeAsyncWithDispatchWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeAsyncWithDispatchBase extends InvokeServletInTomcat {

        private InvokeAsyncWithDispatchBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + contextPath + "/async3")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    @WebServlet(value = "/async", asyncSupported = true, loadOnStartup = 0)
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

    @WebServlet(value = "/async2", asyncSupported = true, loadOnStartup = 0)
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

    @WebServlet(value = "/async3", asyncSupported = true, loadOnStartup = 0)
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

    @WebServlet(value = "/async-forward", loadOnStartup = 0)
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
