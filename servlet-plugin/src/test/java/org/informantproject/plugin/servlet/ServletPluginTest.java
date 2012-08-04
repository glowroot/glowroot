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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

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
        container.closeAndDeleteFiles();
    }

    @Test
    public void testServlet() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteServlet.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription()).isEqualTo("GET /servletundertest");
    }

    @Test
    public void testFilter() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilter.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription()).isEqualTo("GET /filtertest");
    }

    @Test
    public void testCombination() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilterWithNestedServlet.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription()).isEqualTo("GET /combotest");
    }

    @Test
    public void testRequestParameters() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(GetParameters.class);
        // then don't fall into an infinite loop! (yes, at one time it did)
        container.getInformant().getLastTrace();
    }

    @Test
    public void testHasSessionUsernameAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUsernameAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUsernameAttributeNull() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUsernameAttributeNull.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        // this is intentional, setting username attribute to null shouldn't clear out username for
        // that particular request (since the request was in fact, originally, for that username)
        assertThat(trace.getUsername()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUsernameAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameone.two");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUsernameAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameone.two");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(SetNestedSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUsernameAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "missingusernameattr");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isNull();
    }

    @Test
    public void testHasMissingNestedSessionUsernameAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionUsernameAttribute", "usernameone.missingtwo");
        storePluginConfig(pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUsernameAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getUsername()).isNull();
    }

    @Test
    public void testHasSessionAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "testattr");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "*");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", null);
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "testattr");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "*");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", null);
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "*");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "one.two");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "one.two");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "missingtestattr");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("sessionAttributes", "one.missingtwo");
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(InvalidateSession.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getDescription()).isEqualTo("GET /servletundertest");
        assertThat(trace.getSpans().get(0).getContextMap()
                .get("session id (at beginning of this request)")).isEqualTo("1234");
        assertThat(trace.getSpans().get(0).getContextMap()
                .get("session id (updated during this request)")).isEqualTo("");
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription()).isEqualTo("GET /servletundertest");
    }

    @Test
    public void testServletContextInitialized() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("captureStartup", true);
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("captureStartup", true);
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(0);
        PluginConfig pluginConfig = getPluginConfig();
        pluginConfig.setProperty("captureStartup", true);
        storePluginConfig(pluginConfig);
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
        container.getInformant().setThresholdMillis(10000);
        // when
        container.executeAppUnderTest(ThrowsException.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.isError()).isTrue();
    }

    @Test
    public void testReturns404() throws Exception {
        // given
        container.getInformant().setThresholdMillis(10000);
        // when
        container.executeAppUnderTest(Returns404.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.isError()).isTrue();
        assertThat(trace.getSpans().get(0).isError()).isFalse();
        assertThat(trace.getSpans().get(1).isError()).isTrue();
        assertThat(trace.getSpans().get(1).getDescription()).isEqualTo(
                "HttpServletResponse.setStatus(404)");
    }

    private PluginConfig getPluginConfig() throws Exception {
        return container.getInformant().getPluginConfig(PLUGIN_ID);
    }

    private void storePluginConfig(PluginConfig pluginConfig) throws Exception {
        container.getInformant().storePluginProperties(PLUGIN_ID, pluginConfig.getPropertiesJson());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getSpans().get(0).getContextMap()
                .get("session attributes");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getUpdatedSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getSpans().get(0).getContextMap()
                .get("session attributes (updated during this request)");
    }

    @SuppressWarnings("serial")
    public static class ExecuteServlet extends ServletUnderTest {}

    public static class ExecuteFilter implements AppUnderTest {
        public void executeApp() throws ServletException, IOException {
            Filter filter = new MockFilter();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/filtertest");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
        }
    }

    public static class ExecuteFilterWithNestedServlet implements AppUnderTest {
        public void executeApp() throws ServletException, IOException {
            Filter filter = new MockFilterWithServlet();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/combotest");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
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
            init(null);
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
            init(null);
        }
        public void init(FilterConfig filterConfig) {}
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {}
        public void destroy() {}
    }

    @SuppressWarnings("serial")
    public static class GetParameters extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("xy");
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionUsernameAttribute extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUsernameAttribute extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUsernameAttributeNull extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameattr", "something");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameattr", null);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNestedSessionUsernameAttribute extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionUsernameAttribute extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("usernameone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionAttribute extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttribute extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttributeNull extends ServletUnderTest {
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
    public static class HasNestedSessionAttribute extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionAttribute extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three"));
        }
    }

    @SuppressWarnings("serial")
    public static class InvalidateSession extends ServletUnderTest {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setSession(new MockHttpSession(request
                    .getServletContext(), "1234"));
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().invalidate();
        }
    }

    @SuppressWarnings("serial")
    public static class ThrowsException extends ServletUnderTest {
        private final RuntimeException exception = new RuntimeException("something happened");
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
    public static class Returns404 extends ServletUnderTest {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setStatus(404);
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
