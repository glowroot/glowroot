/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.servlet;

import static org.fest.assertions.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nullable;
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

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Config.PluginConfig;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.Trace.Span;
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

    private static final String PLUGIN_ID = "org.informantproject.plugins:servlet-plugin";

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraceSnapshots();
    }

    @Test
    public void testServlet() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteServlet.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testservlet");
    }

    @Test
    public void testFilter() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilter.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testfilter");
    }

    @Test
    public void testCombination() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilterWithNestedServlet.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getMessage().getText()).isEqualTo("GET /testfilter");
    }

    @Test
    public void testRequestParameters() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(GetParameters.class);
        // then don't fall into an infinite loop! (yes, at one time it did)
        container.getInformant().getLastTraceSummary();
    }

    @Test
    public void testHasSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttributeNull() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUserIdAttributeNull.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        // this is intentional, setting user id attribute to null shouldn't clear out user id for
        // that particular request (since the request was in fact, originally, for that user id)
        assertThat(trace.getUserId()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "missinguseridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isNull();
    }

    @Test
    public void testHasMissingNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.missingtwo");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isNull();
    }

    @Test
    public void testHasSessionAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "testattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "*");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", null);
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "testattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "*");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", null);
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "*");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionAttributeNull.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).containsValue("testattr")).isFalse();
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "one.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("one.two")).isEqualTo("three");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "one.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("one.two")).isEqualTo("three");
    }

    @Test
    public void testHasMissingSessionAttribute() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "missingtestattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionAttributes", "one.missingtwo");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSessionInvalidate() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(InvalidateSession.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getDescription()).isEqualTo("GET /testservlet");
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
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("captureStartup", true);
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(TestServletContextListener.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getDescription()).isEqualTo(
                "servlet context initialized (" + TestServletContextListener.class.getName() + ")");
    }

    @Test
    public void testServletInit() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("captureStartup", true);
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(TestServletInit.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getDescription()).isEqualTo(
                "servlet init (" + TestServletInit.class.getName() + ")");
    }

    @Test
    public void testFilterInit() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("captureStartup", true);
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(TestFilterInit.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getDescription()).isEqualTo(
                "filter init (" + TestFilterInit.class.getName() + ")");
    }

    @Test
    public void testThrowsException() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(10000);
        // when
        container.executeAppUnderTest(ThrowsException.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getError()).isNotNull();
        assertThat(trace.getError().getText()).isNotNull();
        assertThat(trace.getError().getException()).isNotNull();
    }

    @Test
    public void testSend404Error() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(Send404Error.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getError()).isNotNull();
        assertThat(trace.getSpans().get(0).getError()).isNotNull();
        assertThat(trace.getSpans().get(0).getError().getText()).isEqualTo(
                "sendError, HTTP status code 404");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getSpans().get(0).getMessage().getDetail()
                .get("session attributes");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getUpdatedSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getSpans().get(0).getMessage().getDetail()
                .get("session attributes (updated during this request)");
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
    public static class GetParameters extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("xy");
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionUserIdAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUserIdAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUserIdAttributeNull extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "something");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", null);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNestedSessionUserIdAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionUserIdAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttributeNull extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "something");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", null);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNestedSessionAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three"));
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
    public static class Send404Error extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.sendError(404);
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
