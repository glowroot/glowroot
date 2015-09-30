/*
 * Copyright 2011-2015 the original author or authors.
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

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class RequestParameterTest {

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
    public void testRequestParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameter.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestParameters =
                (Map<String, Object>) header.detail().get("Request parameters");
        assertThat(requestParameters).hasSize(3);
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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.detail()).hasSize(1);
        assertThat(header.detail()).containsKey("Request http method");
    }

    @Test
    public void testRequestParameterMap() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameterMap.class);
        // then don't throw IllegalStateException (see MockCatalinaHttpServletRequest)
        container.getTraceService().getLastHeader();
    }

    @Test
    public void testBadRequestParameterMap() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetBadParameterMap.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestParameters =
                (Map<String, Object>) header.detail().get("Request parameters");
        assertThat(requestParameters).hasSize(1);
        assertThat(requestParameters.get("k")).isEqualTo("");
    }

    @Test
    public void testOutsideServlet() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetParameterOutsideServlet.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header).isNull();
        // basically just testing that it should not generate any errors
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
    public static class GetBadParameterMap extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request = new BadMockHttpServletRequest("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service(request, response);
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getParameterMap();
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
            parameterMap.put("k", null);
            return parameterMap;
        }
    }
}
