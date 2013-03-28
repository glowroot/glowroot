/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.servlet;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.Container;
import io.informant.testkit.Span;
import io.informant.testkit.Trace;

import java.io.IOException;
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

/**
 * Basic test of the servlet plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ServletPluginTest {

    private static final String PLUGIN_ID = "io.informant.plugins:servlet-plugin";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Container.create(PLUGIN_ID);
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
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testservlet");
    }

    @Test
    public void testFilter() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteFilter.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testfilter");
    }

    @Test
    public void testCombination() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteFilterWithNestedServlet.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testfilter");
    }

    @Test
    public void testRequestParameters() throws Exception {
        // given
        container.setPluginProperty("captureRequestParameters", true);
        // when
        container.executeAppUnderTest(GetParameter.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        @SuppressWarnings("unchecked")
        Map<String, String> requestParameters =
                (Map<String, String>) span.getMessage().getDetail().get("request parameters");
        assertThat(requestParameters.get("xy")).isEqualTo("abc");
    }

    @Test
    public void testWithoutCaptureRequestParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameter.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getDetail()).isNull();
    }

    @Test
    public void testRequestParameterMap() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameterMap.class);
        // then don't throw IllegalStateException (see MockCatalinaHttpServletRequest)
        container.getLastTrace();
    }

    @Test
    public void testSessionInvalidate() throws Exception {
        // given
        container.setPluginProperty("captureSessionId", true);
        // when
        container.executeAppUnderTest(InvalidateSession.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo("GET /testservlet");
        assertThat(trace.getSpans().get(0).getMessage().getDetail()
                .get("session id (at beginning of this request)")).isEqualTo("1234");
        assertThat(trace.getSpans().get(0).getMessage().getDetail()
                .get("session id (updated during this request)")).isEqualTo("");
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testservlet");
    }

    @Test
    public void testServletContextInitialized() throws Exception {
        // given
        container.setPluginProperty("captureStartup", true);
        // when
        container.executeAppUnderTest(TestServletContextListener.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo(
                "servlet context initialized (" + TestServletContextListener.class.getName() + ")");
    }

    @Test
    public void testServletInit() throws Exception {
        // given
        container.setPluginProperty("captureStartup", true);
        // when
        container.executeAppUnderTest(TestServletInit.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getHeadline()).isEqualTo(
                "servlet init (" + TestServletInit.class.getName() + ")");
    }

    @Test
    public void testFilterInit() throws Exception {
        // given
        container.setPluginProperty("captureStartup", true);
        // when
        container.executeAppUnderTest(TestFilterInit.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getHeadline()).isEqualTo(
                "filter init (" + TestFilterInit.class.getName() + ")");
    }

    @Test
    public void testThrowsException() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThrowsException.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getError()).isNotNull();
        assertThat(trace.getError().getText()).isNotNull();
        assertThat(trace.getError().getException()).isNotNull();
    }

    @Test
    public void testSend404Error() throws Exception {
        // given
        // when
        container.executeAppUnderTest(Send500Error.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getError()).isNotNull();
        assertThat(trace.getError().getText()).isEqualTo("sendError, HTTP status code 500");
        assertThat(trace.getError().getException()).isNull();
        assertThat(trace.getSpans().get(0).getError()).isNotNull();
        assertThat(trace.getSpans().get(0).getError().getText()).isEqualTo(
                "sendError, HTTP status code 500");
        assertThat(trace.getSpans().get(0).getError().getException()).isNull();
        assertThat(trace.getSpans().get(0).getStackTrace()).isNull();
        assertThat(trace.getSpans().get(1).getError()).isNotNull();
        assertThat(trace.getSpans().get(1).getError().getText()).isEqualTo(
                "sendError, HTTP status code 500");
        assertThat(trace.getSpans().get(1).getError().getException()).isNull();
        assertThat(trace.getSpans().get(1).getStackTrace()).isNotNull();
        assertThat(trace.getSpans().get(1).getStackTrace().get(0)).contains(".sendError(");
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
        public void executeApp() {
            contextInitialized(null);
        }
        public void contextInitialized(ServletContextEvent sce) {}
        public void contextDestroyed(ServletContextEvent sce) {}
    }

    @SuppressWarnings("serial")
    public static class TestServletInit extends HttpServlet implements AppUnderTest {
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
        public void executeApp() {
            init(new MockFilterConfig());
        }
        public void init(FilterConfig filterConfig) {}
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {}
        public void destroy() {}
    }

    @SuppressWarnings("serial")
    public static class GetParameter extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("xy", "abc");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("xy");
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
