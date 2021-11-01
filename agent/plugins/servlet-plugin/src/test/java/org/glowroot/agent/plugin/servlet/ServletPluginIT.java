/*
 * Copyright 2011-2018 the original author or authors.
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

import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ServletPluginIT {

    private static final String PLUGIN_ID = "servlet";

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
    public void testServlet() throws Exception {
        // when
        Trace trace = container.execute(ExecuteServlet.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/testservlet");
        assertThat(header.getTransactionName()).isEqualTo("/testservlet");
        assertThat(getDetailValue(header, "Request http method")).isEqualTo("GET");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testFilter() throws Exception {
        // when
        Trace trace = container.execute(ExecuteFilter.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/testfilter");
        assertThat(header.getTransactionName()).isEqualTo("/testfilter");
        assertThat(getDetailValue(header, "Request http method")).isEqualTo("GET");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testCombination() throws Exception {
        // when
        Trace trace = container.execute(ExecuteFilterWithNestedServlet.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/testfilter");
        assertThat(header.getTransactionName()).isEqualTo("/testfilter");
        assertThat(getDetailValue(header, "Request http method")).isEqualTo("GET");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testNoQueryString() throws Exception {
        // when
        Trace trace = container.execute(TestNoQueryString.class, "Web");
        // then
        Trace.Header header = trace.getHeader();
        assertThat(getDetailValue(header, "Request query string")).isNull();
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testEmptyQueryString() throws Exception {
        // when
        Trace trace = container.execute(TestEmptyQueryString.class, "Web");
        // then
        Trace.Header header = trace.getHeader();
        assertThat(getDetailValue(header, "Request query string")).isEqualTo("");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testNonEmptyQueryString() throws Exception {
        // when
        Trace trace = container.execute(TestNonEmptyQueryString.class, "Web");
        // then
        Trace.Header header = trace.getHeader();
        assertThat(getDetailValue(header, "Request query string")).isEqualTo("a=b&c=d");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testServletThrowsException() throws Exception {
        // when
        Trace trace = container.execute(ServletThrowsException.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(500);
        assertThat(header.getError().getMessage()).isNotEmpty();
        assertThat(header.getError().hasException()).isTrue();
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testFilterThrowsException() throws Exception {
        // when
        Trace trace = container.execute(FilterThrowsException.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(500);
        assertThat(header.getError().getMessage()).isNotEmpty();
        assertThat(header.getError().hasException()).isTrue();
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void testSendRedirect() throws Exception {
        // when
        Trace trace = container.execute(SendRedirect.class, "Web");

        // then
        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(302);
        assertThat(ResponseHeaderIT.getResponseHeaders(trace).get("Location")).isEqualTo("tohere");
    }

    @Test
    public void testSend500Error() throws Exception {
        // when
        Trace trace = container.execute(Send500Error.class, "Web");

        // then
        assertThat(trace.getHeader().getError().getMessage())
                .isEqualTo("sendError, HTTP status code 500");
        assertThat(trace.getHeader().getError().hasException()).isFalse();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(500);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getError().getMessage()).isEqualTo("sendError, HTTP status code 500");
        assertThat(entry.getError().hasException()).isFalse();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testSetStatus500Error() throws Exception {
        // when
        Trace trace = container.execute(SetStatus500Error.class, "Web");

        // then
        assertThat(trace.getHeader().getError().getMessage())
                .isEqualTo("setStatus, HTTP status code 500");
        assertThat(trace.getHeader().getError().hasException()).isFalse();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(500);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getError().getMessage()).isEqualTo("setStatus, HTTP status code 500");
        assertThat(entry.getError().hasException()).isFalse();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testSend400Error() throws Exception {
        // when
        Trace trace = container.execute(Send400Error.class, "Web");

        // then
        assertThat(trace.getHeader().hasError()).isFalse();
        assertThat(trace.getEntryList()).isEmpty();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(400);
    }

    @Test
    public void testSetStatus400Error() throws Exception {
        // when
        Trace trace = container.execute(SetStatus400Error.class, "Web");

        // then
        assertThat(trace.getHeader().hasError()).isFalse();
        assertThat(trace.getEntryList()).isEmpty();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(400);
    }

    @Test
    public void testSend400ErrorWithCaptureOn() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "traceErrorOn4xxResponseCode",
                true);

        // when
        Trace trace = container.execute(Send400Error.class, "Web");

        // then
        assertThat(trace.getHeader().getError().getMessage())
                .isEqualTo("sendError, HTTP status code 400");
        assertThat(trace.getHeader().getError().hasException()).isFalse();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(400);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getError().getMessage()).isEqualTo("sendError, HTTP status code 400");
        assertThat(entry.getError().hasException()).isFalse();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testSetStatus400ErrorWithCaptureOn() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "traceErrorOn4xxResponseCode",
                true);

        // when
        Trace trace = container.execute(SetStatus400Error.class, "Web");

        // then
        assertThat(trace.getHeader().getError().getMessage())
                .isEqualTo("setStatus, HTTP status code 400");
        assertThat(trace.getHeader().getError().hasException()).isFalse();

        assertThat(getDetailValueLong(trace.getHeader(), "Response code")).isEqualTo(400);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getError().getMessage()).isEqualTo("setStatus, HTTP status code 400");
        assertThat(entry.getError().hasException()).isFalse();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testBizzareServletContainer() throws Exception {
        // when
        container.executeNoExpectedTrace(BizzareServletContainer.class);
        // then
    }

    @Test
    public void testBizzareThrowingServletContainer() throws Exception {
        // when
        container.executeNoExpectedTrace(BizzareThrowingServletContainer.class);
        // then
    }

    private static String getDetailValue(Trace.Header header, String name) {
        for (Trace.DetailEntry detail : header.getDetailEntryList()) {
            if (detail.getName().equals(name)) {
                return detail.getValueList().get(0).getString();
            }
        }
        return null;
    }

    private static Long getDetailValueLong(Trace.Header header, String name) {
        for (Trace.DetailEntry detail : header.getDetailEntryList()) {
            if (detail.getName().equals(name)) {
                return detail.getValueList().get(0).getLong();
            }
        }
        return null;
    }

    @SuppressWarnings("serial")
    public static class ExecuteServlet extends TestServlet {}

    public static class ExecuteFilter extends TestFilter {}

    public static class ExecuteFilterWithNestedServlet extends TestFilter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            new TestServlet().service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class TestNoQueryString extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setQueryString(null);
        }
    }

    @SuppressWarnings("serial")
    public static class TestEmptyQueryString extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setQueryString("");
        }
    }

    @SuppressWarnings("serial")
    public static class TestNonEmptyQueryString extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setQueryString("a=b&c=d");
        }
    }

    @SuppressWarnings("serial")
    public static class InvalidateSession extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setSession(new MockHttpSession(null, "1234"));
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().invalidate();
        }
    }

    @SuppressWarnings("serial")
    public static class ServletThrowsException extends TestServlet {
        private final RuntimeException exception = new RuntimeException("Something happened");
        @Override
        public void executeApp() throws Exception {
            try {
                super.executeApp();
            } catch (RuntimeException e) {
                // only suppress expected exception
                if (e != exception) {
                    throw e;
                }
            }
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            throw exception;
        }
    }

    public static class FilterThrowsException extends TestFilter {
        private final RuntimeException exception = new RuntimeException("Something happened");
        @Override
        public void executeApp() throws Exception {
            try {
                super.executeApp();
            } catch (RuntimeException e) {
                // only suppress expected exception
                if (e != exception) {
                    throw e;
                }
            }
        }
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
            throw exception;
        }
    }

    @SuppressWarnings("serial")
    public static class SendRedirect extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.sendRedirect("tohere");
        }
    }

    @SuppressWarnings("serial")
    public static class Send500Error extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.sendError(500);
        }
    }

    @SuppressWarnings("serial")
    public static class SetStatus500Error extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.setStatus(500);
        }
    }

    @SuppressWarnings("serial")
    public static class Send400Error extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.sendError(400);
        }
    }

    @SuppressWarnings("serial")
    public static class SetStatus400Error extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.setStatus(400);
        }
    }

    public static class BizzareServletContainer implements AppUnderTest, Servlet {
        @Override
        public void executeApp() throws Exception {
            service(null, null);
        }
        @Override
        public void init(ServletConfig config) {}
        @Override
        public ServletConfig getServletConfig() {
            return null;
        }
        @Override
        public void service(ServletRequest req, ServletResponse res) {}
        @Override
        public String getServletInfo() {
            return null;
        }
        @Override
        public void destroy() {}
    }

    public static class BizzareThrowingServletContainer implements AppUnderTest, Servlet {
        @Override
        public void executeApp() throws Exception {
            try {
                service(null, null);
            } catch (RuntimeException e) {
            }
        }
        @Override
        public void init(ServletConfig config) {}
        @Override
        public ServletConfig getServletConfig() {
            return null;
        }
        @Override
        public void service(ServletRequest req, ServletResponse res) {
            throw new RuntimeException();
        }
        @Override
        public String getServletInfo() {
            return null;
        }
        @Override
        public void destroy() {}
    }

    public static class NestedTwo {
        private final String two;
        public NestedTwo(String two) {
            this.two = two;
        }
        public String getTwo() {
            return two;
        }
    }
}
