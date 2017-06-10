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

import javax.servlet.ServletException;
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
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ServletDispatcherIT {

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
    public void testForwardServlet() throws Exception {
        testForwardServlet("", InvokeForwardServlet.class);
    }

    @Test
    public void testForwardServletWithContextPath() throws Exception {
        testForwardServlet("/zzz", InvokeForwardServletWithContextPath.class);
    }

    @Test
    public void testForwardServletUsingContext() throws Exception {
        testForwardServletUsingContext("", InvokeForwardServletUsingContext.class);
    }

    @Test
    public void testForwardServletUsingContextWithContextPath() throws Exception {
        testForwardServletUsingContext("/zzz",
                InvokeForwardServletUsingContextWithContextPath.class);
    }

    @Test
    public void testForwardServletUsingNamed() throws Exception {
        testForwardServletUsingNamed("", InvokeForwardServletUsingNamed.class);
    }

    @Test
    public void testForwardServletUsingNamedWithContextPath() throws Exception {
        testForwardServletUsingNamed("/zzz", InvokeForwardServletUsingNamedWithContextPath.class);
    }

    @Test
    public void testIncludeServlet() throws Exception {
        testIncludeServlet("", InvokeIncludeServlet.class);
    }

    @Test
    public void testIncludeServletWithContextPath() throws Exception {
        testIncludeServlet("/zzz", InvokeIncludeServletWithContextPath.class);
    }

    private void testForwardServlet(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/first-forward");
        assertThat(header.getTransactionName()).isEqualTo(contextPath + "/first-forward");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("servlet dispatch: /second");

        assertThat(i.hasNext()).isFalse();
    }

    private void testForwardServletUsingContext(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/first-forward-using-context");
        assertThat(header.getTransactionName())
                .isEqualTo(contextPath + "/first-forward-using-context");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("servlet dispatch: /second");

        assertThat(i.hasNext()).isFalse();
    }

    private void testForwardServletUsingNamed(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/first-forward-using-named");
        assertThat(header.getTransactionName())
                .isEqualTo(contextPath + "/first-forward-using-named");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("servlet dispatch: yyy");

        assertThat(i.hasNext()).isFalse();
    }

    private void testIncludeServlet(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo(contextPath + "/first-include");
        assertThat(header.getTransactionName()).isEqualTo(contextPath + "/first-include");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("servlet dispatch: /second");

        assertThat(i.hasNext()).isFalse();
    }

    public static class InvokeForwardServlet extends InvokeForwardServletBase {
        public InvokeForwardServlet() {
            super("");
        }
    }

    public static class InvokeForwardServletWithContextPath extends InvokeForwardServletBase {
        public InvokeForwardServletWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeForwardServletBase extends InvokeServletInTomcat {

        private InvokeForwardServletBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + port + contextPath + "/first-forward")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeForwardServletUsingContext
            extends InvokeForwardServletUsingContextBase {
        public InvokeForwardServletUsingContext() {
            super("");
        }
    }

    public static class InvokeForwardServletUsingContextWithContextPath
            extends InvokeForwardServletUsingContextBase {
        public InvokeForwardServletUsingContextWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeForwardServletUsingContextBase extends InvokeServletInTomcat {

        private InvokeForwardServletUsingContextBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + port + contextPath
                            + "/first-forward-using-context")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeForwardServletUsingNamed extends InvokeForwardServletUsingNamedBase {
        public InvokeForwardServletUsingNamed() {
            super("");
        }
    }

    public static class InvokeForwardServletUsingNamedWithContextPath
            extends InvokeForwardServletUsingNamedBase {
        public InvokeForwardServletUsingNamedWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeForwardServletUsingNamedBase extends InvokeServletInTomcat {

        private InvokeForwardServletUsingNamedBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet(
                            "http://localhost:" + port + contextPath + "/first-forward-using-named")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeIncludeServlet extends InvokeIncludeServletBase {
        public InvokeIncludeServlet() {
            super("");
        }
    }

    public static class InvokeIncludeServletWithContextPath extends InvokeIncludeServletBase {
        public InvokeIncludeServletWithContextPath() {
            super("/zzz");
        }
    }

    private static class InvokeIncludeServletBase extends InvokeServletInTomcat {

        private InvokeIncludeServletBase(String contextPath) {
            super(contextPath);
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient
                    .prepareGet("http://localhost:" + port + contextPath + "/first-include")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    @WebServlet(value = "/first-forward", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class FirstForwardServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/second").forward(request, response);
        }
    }

    @WebServlet(value = "/first-forward-using-context", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class FirstForwardUsingContextServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            getServletContext().getRequestDispatcher("/second").forward(request, response);
        }
    }

    @WebServlet(value = "/first-forward-using-named", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class FirstForwardUsingNamedServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            getServletContext().getNamedDispatcher("yyy").forward(request, response);
        }
    }

    @WebServlet(value = "/first-include", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class FirstIncludeServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/second").include(request, response);
        }
    }

    @WebServlet(urlPatterns = "/second", name = "yyy", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class SecondServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.getWriter().print("second");
        }
    }
}
