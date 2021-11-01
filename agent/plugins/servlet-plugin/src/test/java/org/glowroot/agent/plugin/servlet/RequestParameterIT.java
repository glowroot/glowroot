/*
 * Copyright 2011-2019 the original author or authors.
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

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class RequestParameterIT {

    private static final String PLUGIN_ID = "servlet";

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = JavaagentContainer.create();
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
    public void testRequestParameters() throws Exception {
        // when
        Trace trace = container.execute(GetParameter.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(3);
        assertThat(requestParameters.get("xYz")).isEqualTo("aBc");
        assertThat(requestParameters.get("jpassword1")).isEqualTo("****");
        @SuppressWarnings("unchecked")
        List<String> multi = (List<String>) requestParameters.get("multi");
        assertThat(multi).containsExactly("m1", "m2");
        String queryString = ResponseHeaderIT.getDetailValue(trace, "Request query string");
        assertThat(queryString).isEqualTo("xYz=aBc&jpassword1=****&multi=m1&multi=m2");
    }

    @Test
    public void testRequestParametersWithoutMaskedQueryString() throws Exception {
        // when
        Trace trace = container.execute(GetParameterWithoutMaskedQueryString.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(3);
        assertThat(requestParameters.get("xYz")).isEqualTo("aBc");
        assertThat(requestParameters.get("jpassword1")).isEqualTo("****");
        @SuppressWarnings("unchecked")
        List<String> multi = (List<String>) requestParameters.get("multi");
        assertThat(multi).containsExactly("m1", "m2");
        String queryString = ResponseHeaderIT.getDetailValue(trace, "Request query string");
        assertThat(queryString).isEqualTo("xYz=aBc&multi=m1&multi=m2");
    }

    @Test
    public void testWithoutCaptureRequestParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestParameters",
                ImmutableList.<String>of());
        // when
        Trace trace = container.execute(GetParameter.class, "Web");
        // then
        assertThat(trace.getHeader().getDetailEntryList()).hasSize(3);
        assertThat(trace.getHeader().getDetailEntryList().get(0).getName())
                .isEqualTo("Request http method");
        assertThat(trace.getHeader().getDetailEntryList().get(1).getName())
                .isEqualTo("Request query string");
        assertThat(trace.getHeader().getDetailEntryList().get(2).getName())
                .isEqualTo("Response code");
    }

    @Test
    public void testRequestParameterMap() throws Exception {
        // when
        container.execute(GetParameterMap.class, "Web");
        // then don't throw IllegalStateException (see MockCatalinaHttpServletRequest)
    }

    @Test
    public void testBadRequestParameterMap() throws Exception {
        // when
        Trace trace = container.execute(GetBadParameterMap.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(1);
        assertThat(requestParameters.get("n")).isEqualTo("x");
    }

    @Test
    public void testExtraBadRequestParameterMap() throws Exception {
        // when
        Trace trace = container.execute(GetExtraBadParameterMap.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(1);
        assertThat(requestParameters.get("n")).isEqualTo("x");
    }

    @Test
    public void testAnotherBadRequestParameterMap() throws Exception {
        // when
        Trace trace = container.execute(GetAnotherBadParameterMap.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(1);
        assertThat(requestParameters.get("n")).isEqualTo("x");
    }

    @Test
    public void testLargeRequestParameters() throws Exception {
        // when
        Trace trace = container.execute(GetLargeParameter.class, "Web");

        // then
        Map<String, Object> requestParameters =
                ResponseHeaderIT.getDetailMap(trace, "Request parameters");
        assertThat(requestParameters).hasSize(1);
        assertThat(requestParameters.get("large")).isEqualTo(
                Strings.repeat("0123456789", 1000) + " [truncated to 10000 characters]");
    }

    @Test
    public void testOutsideServlet() throws Exception {
        // when
        container.executeNoExpectedTrace(GetParameterOutsideServlet.class);
        // then
        // basically just testing that it should not generate any errors
    }

    @SuppressWarnings("serial")
    public static class GetParameter extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("xYz", "aBc");
            ((MockHttpServletRequest) request).setParameter("jpassword1", "mask me");
            ((MockHttpServletRequest) request).setParameter("multi", new String[] {"m1", "m2"});
            ((MockHttpServletRequest) request)
                    .setQueryString("xYz=aBc&jpassword1=mask%20me&multi=m1&multi=m2");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("xYz");
        }
    }

    @SuppressWarnings("serial")
    public static class GetParameterWithoutMaskedQueryString extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("xYz", "aBc");
            ((MockHttpServletRequest) request).setParameter("jpassword1", "mask me");
            ((MockHttpServletRequest) request).setParameter("multi", new String[] {"m1", "m2"});
            ((MockHttpServletRequest) request).setQueryString("xYz=aBc&multi=m1&multi=m2");
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
            request.getParameterValues("z");
        }
    }

    @SuppressWarnings("serial")
    public static class GetBadParameterMap extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request = new BadMockHttpServletRequest("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service((ServletRequest) request, (ServletResponse) response);
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameterValues("z");
        }
    }

    @SuppressWarnings("serial")
    public static class GetExtraBadParameterMap extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request =
                    new ExtraBadMockHttpServletRequest("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service((ServletRequest) request, (ServletResponse) response);
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameterValues("z");
        }
    }

    @SuppressWarnings("serial")
    public static class GetAnotherBadParameterMap extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request =
                    new AnotherBadMockHttpServletRequest("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service((ServletRequest) request, (ServletResponse) response);
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameterValues("z");
        }
    }

    @SuppressWarnings("serial")
    public static class GetLargeParameter extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setParameter("large",
                    Strings.repeat("0123456789", 2000));
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameter("large");
        }
    }

    public static class GetParameterOutsideServlet implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request =
                    new MockCatalinaHttpServletRequest("GET", "/testservlet");
            request.setParameter("xYz", "aBc");
            request.setParameter("jpassword1", "mask me");
            request.setParameter("multi", new String[] {"m1", "m2"});
            request.getParameter("xYz");
        }
    }

    public static class BadMockHttpServletRequest extends MockHttpServletRequest {

        public BadMockHttpServletRequest(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> parameterMap = Maps.newHashMap();
            parameterMap.put(null, new String[] {"v"});
            parameterMap.put("m", null);
            parameterMap.put("n", new String[] {null, "x"});
            return parameterMap;
        }
    }

    public static class ExtraBadMockHttpServletRequest extends MockHttpServletRequest {

        public ExtraBadMockHttpServletRequest(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw new NullPointerException();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(Lists.newArrayList(null, "m", "n"));
        }

        @Override
        public String[] getParameterValues(String name) {
            if (name.equals("m")) {
                return null;
            }
            if (name.equals("n")) {
                return new String[] {null, "x"};
            }
            return new String[0];
        }
    }

    public static class AnotherBadMockHttpServletRequest extends MockHttpServletRequest {

        public AnotherBadMockHttpServletRequest(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<Object, Object> parameterMap = new HashMap<Object, Object>();
            parameterMap.put("m", new Object());
            parameterMap.put("n", new String[] {"x"});
            @SuppressWarnings("unchecked")
            Map<String, String[]> badParameterMap =
                    (Map<String, String[]>) ((Map<?, ?>) parameterMap);
            return badParameterMap;
        }
    }
}
