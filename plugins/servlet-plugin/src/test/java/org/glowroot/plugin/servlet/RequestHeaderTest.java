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

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests of the servlet plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
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
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) span.getMessage().getDetail().get("request headers");
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
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) span.getMessage().getDetail().get("request headers");
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
        assertThat(trace.getSpans()).hasSize(1);
        Span span = trace.getSpans().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> requestHeaders =
                (Map<String, Object>) span.getMessage().getDetail().get("request headers");
        @SuppressWarnings("unchecked")
        List<String> one = (List<String>) requestHeaders.get("One");
        assertThat(one).containsExactly("ab", "xy");
        assertThat(requestHeaders.get("Two")).isEqualTo("1");
        assertThat(requestHeaders.get("Three")).isNull();
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
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {}
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
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {}
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
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {}
    }
}
