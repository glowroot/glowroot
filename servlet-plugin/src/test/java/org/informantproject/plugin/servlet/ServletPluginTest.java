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

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.informantproject.testkit.AppUnderTest;
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

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.newInstance();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
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
        assertThat(span.getDescription(), is("servlet: " + MockServlet.class.getName()
                + ".service()"));
        assertThat((String) span.getContextMap().get("request method"), is("GET"));
        assertThat((String) span.getContextMap().get("request uri"), is("/servlettest"));
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
        assertThat(span.getDescription(), is("filter: " + MockFilter.class.getName()
                + ".doFilter()"));
        assertThat((String) span.getContextMap().get("request method"), is("GET"));
        assertThat((String) span.getContextMap().get("request uri"), is("/filtertest"));
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
        assertThat(trace.getSpans().size(), is(2));
        Span filterSpan = trace.getSpans().get(0);
        assertThat(filterSpan.getDescription(),
                is("filter: " + MockFilterWithServlet.class.getName() + ".doFilter()"));
        assertThat((String) filterSpan.getContextMap().get("request method"), is("GET"));
        assertThat((String) filterSpan.getContextMap().get("request uri"), is("/combotest"));
        Span servletSpan = trace.getSpans().get(1);
        assertThat(servletSpan.getDescription(), is("servlet: " + MockServlet.class.getName()
                + ".service()"));
        assertThat(servletSpan.getContextMap(), is(nullValue()));
    }

    @Test
    public void testRequestParameters() {
        // TODO build out this unit test
        // use "combination" filter / servlet, because there was a previous bug due to this
        // with TC request parameters
    }

    @Test
    public void testUsername() {
        // TODO build out this unit test
    }

    @Test
    public void testSessionAttributes() {
        // TODO build out this unit test
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
        Span span = trace.getSpans().get(0);
        assertThat(span.getDescription(),
                is("servlet: " + InvalidateSessionMockServlet.class.getName() + ".service()"));
        assertThat((String) span.getContextMap().get("request method"), is("GET"));
        assertThat((String) span.getContextMap().get("request uri"), is("/invalidate"));
        assertThat((String) span.getContextMap().get("session id (at beginning of this request)"),
                is("1"));
        assertThat((String) span.getContextMap().get("session id (updated during this request)"),
                is(""));
    }

    public static class ExecuteServlet implements AppUnderTest {
        public void executeApp() throws ServletException, IOException {
            Servlet servlet = new MockServlet();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/servlettest");
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request, response);
        }
    }

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

    public static class InvalidateSession implements AppUnderTest {
        public void executeApp() throws ServletException, IOException {
            MockHttpSession session = new MockHttpSession();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/invalidate");
            request.setSession(session);
            MockHttpServletResponse response = new MockHttpServletResponse();
            InvalidateSessionMockServlet servlet = new InvalidateSessionMockServlet();
            servlet.service(request, response);
        }
    }
}
