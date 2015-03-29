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
package org.glowroot.plugin.servlet;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class RequestHeaderTest {

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
    public void testStandardRequestHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestHeaders",
                "Content-Type, Content-Length");
        // when
        container.executeAppUnderTest(SetStandardRequestHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) trace.getCustomDetail().get("Request headers");
        assertThat(requestHeaders.get("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(requestHeaders.get("Content-Length")).isEqualTo("1");
        assertThat(requestHeaders.get("Extra")).isNull();
    }

    @Test
    public void testStandardRequestHeadersLowercase() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestHeaders",
                "Content-Type, Content-Length");
        // when
        container.executeAppUnderTest(SetStandardRequestHeadersLowercase.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) trace.getCustomDetail().get("Request headers");
        assertThat(requestHeaders.get("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(requestHeaders.get("content-length")).isEqualTo("1");
        assertThat(requestHeaders.get("extra")).isNull();
    }

    @Test
    public void testLotsOfRequestHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestHeaders",
                "One,Two");
        // when
        container.executeAppUnderTest(SetOtherRequestHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) trace.getCustomDetail().get("Request headers");
        @SuppressWarnings("unchecked")
        List<String> one = (List<String>) requestHeaders.get("One");
        assertThat(one).containsExactly("ab", "xy");
        assertThat(requestHeaders.get("Two")).isEqualTo("1");
        assertThat(requestHeaders.get("Three")).isNull();
    }

    @Test
    public void testBadRequestHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestHeaders",
                "Content-Type, Content-Length");
        // when
        container.executeAppUnderTest(GetBadRequestHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) trace.getCustomDetail().get("Request headers");
        assertThat(requestHeaders).isNull();
    }

    @Test
    public void testBadRequestHeaders2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureRequestHeaders",
                "Content-Type, Content-Length, h1");
        // when
        container.executeAppUnderTest(GetBadRequestHeaders2.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) trace.getCustomDetail().get("Request headers");
        assertThat(requestHeaders).hasSize(1);
        assertThat(requestHeaders.get("h1")).isEqualTo("");
    }

    @Test
    public void testTransactionNameOverrideRequestHeaders() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionNameOverrideRequestHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getTransactionName()).isEqualTo("AbcXyz");
    }

    @SuppressWarnings("serial")
    public static class SetStandardRequestHeaders extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).addHeader("Content-Type",
                    "text/plain;charset=UTF-8");
            ((MockHttpServletRequest) request).addHeader("Content-Length", "1");
            ((MockHttpServletRequest) request).addHeader("Extra", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetStandardRequestHeadersLowercase extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).addHeader("content-type",
                    "text/plain;charset=UTF-8");
            ((MockHttpServletRequest) request).addHeader("content-length", "1");
            ((MockHttpServletRequest) request).addHeader("extra", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetOtherRequestHeaders extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).addHeader("One", "ab");
            ((MockHttpServletRequest) request).addHeader("One", "xy");
            ((MockHttpServletRequest) request).addHeader("Two", "1");
            ((MockHttpServletRequest) request).addHeader("Three", "xyz");
        }
    }

    @SuppressWarnings("serial")
    public static class GetBadRequestHeaders extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request = new BadMockHttpServletRequest("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class GetBadRequestHeaders2 extends TestServlet {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request = new BadMockHttpServletRequest2("GET", "/testservlet");
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class SetTransactionNameOverrideRequestHeaders extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).addHeader("Glowroot-Transaction-Name",
                    "AbcXyz");
        }
    }

    public static class BadMockHttpServletRequest extends MockHttpServletRequest {

        public BadMockHttpServletRequest(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Lists.newArrayList((String) null));
        }
    }

    public static class BadMockHttpServletRequest2 extends MockHttpServletRequest {

        public BadMockHttpServletRequest2(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Lists.newArrayList("h1"));
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (name.equals("h1")) {
                return Collections.enumeration(Collections.<String>emptyList());
            } else {
                return super.getHeaders(name);
            }
        }
    }
}
