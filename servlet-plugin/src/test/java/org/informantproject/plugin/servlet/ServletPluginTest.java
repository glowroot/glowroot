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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.PluginConfiguration;
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
        container.shutdown();
    }

    @Test
    public void testServlet() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteServlet.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription(), is("GET /servletundertest"));
    }

    @Test
    public void testFilter() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilter.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription(), is("GET /filtertest"));
    }

    @Test
    public void testCombination() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteFilterWithNestedServlet.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription(), is("GET /combotest"));
    }

    @Test
    public void testRequestParameters() {
        // TODO build out this unit test
        // use "combination" filter / servlet, because there was a previous bug due to this
        // with TC request parameters
    }

    @Test
    public void testHasSessionUsernameAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasSessionUsernameAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getUsername(), is("abc"));
    }

    @Test
    public void testSetSessionUsernameAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionUsernameAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getUsername(), is("abc"));
    }

    @Test
    public void testSetSessionUsernameAttributeNull() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionUsernameAttribute", "usernameattr");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionUsernameAttributeNull.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        // this is intentional, setting username attribute to null shouldn't clear out username for
        // that particular request (since the request was in fact, originally, for that username)
        assertThat(trace.getUsername(), is("something"));
    }

    @Test
    public void testHasNestedSessionUsernameAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionUsernameAttribute", "usernameone.two");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasNestedSessionUsernameAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getUsername(), is("xyz"));
    }

    @Test
    public void testSetNestedSessionUsernameAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionUsernameAttribute", "usernameone.two");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetNestedSessionUsernameAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getUsername(), is("xyz"));
    }

    @Test
    public void testHasSessionAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "testattr");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace).get("testattr"), is("val"));
        assertThat(getUpdatedSessionAttributes(trace), is(nullValue()));
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "*");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace).get("testattr"), is("val"));
        assertThat(getUpdatedSessionAttributes(trace), is(nullValue()));
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", null);
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace), is(nullValue()));
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "testattr");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace).get("testattr"), is("val"));
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "*");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace).get("testattr"), is("val"));
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", null);
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace), is(nullValue()));
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "*");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetSessionAttributeNull.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace).containsValue("testattr"), is(false));
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "one.two");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace).get("one.two"), is("three"));
        assertThat(getUpdatedSessionAttributes(trace), is(nullValue()));
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        PluginConfiguration pluginConfiguration = getPluginConfiguration();
        pluginConfiguration.setProperty("sessionAttributes", "one.two");
        storePluginConfiguration(pluginConfiguration);
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(getSessionAttributes(trace), is(nullValue()));
        assertThat(getUpdatedSessionAttributes(trace).get("one.two"), is("three"));
    }

    @Test
    public void testSessionInvalidate() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(InvalidateSession.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(1));
        assertThat(trace.getDescription(), is("GET /servletundertest"));
        assertThat((String) trace.getContextMap().get("session id (at beginning of this request)"),
                is("1234"));
        assertThat((String) trace.getContextMap().get("session id (updated during this request)"),
                is(""));
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription(), is("GET /servletundertest"));
    }

    private PluginConfiguration getPluginConfiguration() throws Exception {
        return container.getInformant().getPluginConfiguration(PLUGIN_ID);
    }

    private void storePluginConfiguration(PluginConfiguration pluginConfiguration) throws Exception {
        container.getInformant().storePluginConfiguration(PLUGIN_ID, pluginConfiguration);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getContextMap().get("session attributes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getUpdatedSessionAttributes(Trace trace) {
        return (Map<String, String>) trace.getContextMap().get("session attributes (updated during"
                + " this request)");
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
