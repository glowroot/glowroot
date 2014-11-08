/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletConfig;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class ServletPluginTest {

    private static final String PLUGIN_ID = "servlet";

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
    public void testServlet() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteServlet.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("/testservlet");
        assertThat(trace.getTransactionName()).isEqualTo("/testservlet");
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText()).isEqualTo("/testservlet");
        assertThat(entry.getMessage().getDetail().get("Request http method")).isEqualTo("GET");
    }

    @Test
    public void testFilter() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteFilter.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("/testfilter");
        assertThat(trace.getTransactionName()).isEqualTo("/testfilter");
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText()).isEqualTo("/testfilter");
        assertThat(entry.getMessage().getDetail().get("Request http method")).isEqualTo("GET");
    }

    @Test
    public void testCombination() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteFilterWithNestedServlet.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("/testfilter");
        assertThat(trace.getTransactionName()).isEqualTo("/testfilter");
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText()).isEqualTo("/testfilter");
        assertThat(entry.getMessage().getDetail().get("Request http method")).isEqualTo("GET");
    }

    @Test
    public void testNoQueryString() throws Exception {
        // given
        // when
        container.executeAppUnderTest(TestNoQueryString.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        String queryString = (String) entry.getMessage().getDetail().get("Request query string");
        assertThat(queryString).isNull();
    }

    @Test
    public void testEmptyQueryString() throws Exception {
        // given
        // when
        container.executeAppUnderTest(TestEmptyQueryString.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        String queryString = (String) entry.getMessage().getDetail().get("Request query string");
        assertThat(queryString).isEqualTo("");
    }

    @Test
    public void testNonEmptyQueryString() throws Exception {
        // given
        // when
        container.executeAppUnderTest(TestNonEmptyQueryString.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        String queryString = (String) entry.getMessage().getDetail().get("Request query string");
        assertThat(queryString).isEqualTo("a=b&c=d");
    }

    @Test
    public void testRequestParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameter.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestParameters =
                (Map<String, Object>) entry.getMessage().getDetail().get("Request parameters");
        assertThat(requestParameters.get("xYz")).isEqualTo("aBc");
        assertThat(requestParameters.get("jpassword1")).isEqualTo("****");
        @SuppressWarnings("unchecked")
        List<String> multi = (List<String>) requestParameters.get("multi");
        assertThat(multi).containsExactly("m1", "m2");
    }

    @Test
    public void testWithoutCaptureRequestParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestParameters", "");
        // when
        container.executeAppUnderTest(GetParameter.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getDetail()).hasSize(1);
        assertThat(entry.getMessage().getDetail()).containsKey("Request http method");
    }

    @Test
    public void testRequestParameterMap() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameterMap.class);
        // then don't throw IllegalStateException (see MockCatalinaHttpServletRequest)
        container.getTraceService().getLastTrace();
    }

    @Test
    public void testSessionInvalidate() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionId", true);
        // when
        container.executeAppUnderTest(InvalidateSession.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo("/testservlet");
        assertThat(trace.getTransactionName()).isEqualTo("/testservlet");
        assertThat(entries.get(0).getMessage().getDetail()
                .get("Session ID (at beginning of this request)")).isEqualTo("1234");
        assertThat(entries.get(0).getMessage().getDetail()
                .get("Session ID (updated during this request)")).isEqualTo("");
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText()).isEqualTo("/testservlet");
        assertThat(entry.getMessage().getDetail().get("Request http method")).isEqualTo("GET");
    }

    @Test
    public void testServletContextInitialized() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureStartup", true);
        // when
        container.executeAppUnderTest(TestServletContextListener.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo(
                TestServletContextListener.class.getName() + ".contextInitialized()");
    }

    @Test
    public void testServletInit() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureStartup", true);
        // when
        container.executeAppUnderTest(TestServletInit.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        assertThat(trace.getHeadline()).isEqualTo(
                TestServletInit.class.getName() + ".init()");
        assertThat(trace.getTransactionName()).isEqualTo(
                "servlet init / " + TestServletInit.class.getName());
        assertThat(entries.get(0).getMessage().getText())
                .isEqualTo(TestServletInit.class.getName() + ".init()");
    }

    @Test
    public void testFilterInit() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureStartup", true);
        // when
        container.executeAppUnderTest(TestFilterInit.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo(TestFilterInit.class.getName() + ".init()");
        assertThat(trace.getTransactionName()).isEqualTo("filter init / "
                + TestFilterInit.class.getName());
        assertThat(entries.get(0).getMessage().getText())
                .isEqualTo(TestFilterInit.class.getName() + ".init()");
    }

    @Test
    public void testThrowsException() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThrowsException.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(trace.getError()).isNotNull();
        assertThat(entries.get(0).getError().getException()).isNotNull();
    }

    @Test
    public void testSend500Error() throws Exception {
        // given
        // when
        container.executeAppUnderTest(Send500Error.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        assertThat(trace.getError()).isEqualTo("sendError, HTTP status code 500");
        assertThat(entries.get(0).getError()).isNotNull();
        assertThat(entries.get(0).getError().getText()).isEqualTo(
                "sendError, HTTP status code 500");
        assertThat(entries.get(0).getError().getException()).isNull();
        assertThat(entries.get(0).getStackTrace()).isNull();
        assertThat(entries.get(1).getError()).isNotNull();
        assertThat(entries.get(1).getError().getText()).isEqualTo(
                "sendError, HTTP status code 500");
        assertThat(entries.get(1).getError().getException()).isNull();
        assertThat(entries.get(1).getStackTrace()).isNotNull();
        assertThat(entries.get(1).getStackTrace().get(0)).contains(".sendError(");
    }

    @Test
    public void testSetStatus500Error() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetStatus500Error.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        assertThat(trace.getError()).isEqualTo("setStatus, HTTP status code 500");
        assertThat(entries.get(0).getError()).isNotNull();
        assertThat(entries.get(0).getError().getText()).isEqualTo(
                "setStatus, HTTP status code 500");
        assertThat(entries.get(0).getError().getException()).isNull();
        assertThat(entries.get(0).getStackTrace()).isNull();
        assertThat(entries.get(1).getError()).isNotNull();
        assertThat(entries.get(1).getError().getText()).isEqualTo(
                "setStatus, HTTP status code 500");
        assertThat(entries.get(1).getError().getException()).isNull();
        assertThat(entries.get(1).getStackTrace()).isNotNull();
        assertThat(entries.get(1).getStackTrace().get(0)).contains(".setStatus(");
    }

    @SuppressWarnings("serial")
    public static class ExecuteServlet extends TestServlet {}

    public static class ExecuteFilter extends TestFilter {}

    public static class ExecuteFilterWithNestedServlet extends TestFilter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            new TestFilter().doFilter(request, response, chain);
        }
    }

    public static class TestServletContextListener implements AppUnderTest, ServletContextListener {
        @Override
        public void executeApp() {
            contextInitialized(null);
        }
        @Override
        public void contextInitialized(ServletContextEvent sce) {}
        @Override
        public void contextDestroyed(ServletContextEvent sce) {}
    }

    @SuppressWarnings("serial")
    public static class TestServletInit extends HttpServlet implements AppUnderTest {
        @Override
        public void executeApp() throws ServletException {
            init(new MockServletConfig());
        }
        @Override
        public void init(ServletConfig config) throws ServletException {
            // calling super to make sure it doesn't end up in an infinite loop (this happened once
            // before due to bug in weaver)
            super.init(config);
        }
    }

    public static class TestFilterInit implements AppUnderTest, Filter {
        @Override
        public void executeApp() {
            init(new MockFilterConfig());
        }
        @Override
        public void init(FilterConfig filterConfig) {}
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {}
        @Override
        public void destroy() {}
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
    public static class GetParameter extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("xYz", "aBc");
            ((MockHttpServletRequest) request).setParameter("jpassword1", "mask me");
            ((MockHttpServletRequest) request).setParameter("multi", new String[] {"m1", "m2"});
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("xYz");
        }
    }

    @SuppressWarnings("serial")
    public static class GetParameterMap extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("xy", "abc");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameterMap();
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
    public static class ThrowsException extends TestServlet {
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
