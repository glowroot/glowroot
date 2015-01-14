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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;
import org.glowroot.plugin.servlet.TestServlet.PatchedMockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class ResponseHeaderTest {

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
    public void testStandardResponseHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");
        // when
        container.executeAppUnderTest(SetStandardResponseHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseHeaders =
                (Map<String, Object>) entry.getMessage().getDetail().get("Response headers");
        assertThat(responseHeaders.get("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-Length")).isEqualTo("1");
        assertThat(responseHeaders.get("Content-Language")).isEqualTo("en");
        assertThat(responseHeaders.get("Extra")).isNull();
    }

    @Test
    public void testStandardResponseHeadersUsingSetHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersUsingSetHeader.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseHeaders =
                (Map<String, Object>) entry.getMessage().getDetail().get("Response headers");
        assertThat(responseHeaders.get("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-Length")).isEqualTo("1");
        assertThat(responseHeaders.get("Content-Language")).isEqualTo("en");
        assertThat(responseHeaders.get("Extra")).isNull();
    }

    @Test
    public void testStandardResponseHeadersUsingAddHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersUsingAddHeader.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseHeaders =
                (Map<String, Object>) entry.getMessage().getDetail().get("Response headers");
        assertThat(responseHeaders.get("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-Length")).isEqualTo("1");
        assertThat(responseHeaders.get("Content-Language")).isEqualTo("en");
        assertThat(responseHeaders.get("Extra")).isNull();
    }

    @Test
    public void testStandardResponseHeadersLowercase() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersLowercase.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseHeaders =
                (Map<String, Object>) entry.getMessage().getDetail().get("Response headers");
        assertThat(responseHeaders.get("content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("extra")).isNull();
    }

    @Test
    public void testWithoutAnyHeaderCapture() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        container.executeAppUnderTest(SetStandardResponseHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getDetail()).doesNotContainKey("Response headers");
    }

    @Test
    public void testWithoutAnyInterestingHeaderCapture() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "ABC");
        // when
        container.executeAppUnderTest(SetStandardResponseHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getDetail()).doesNotContainKey("Response headers");
    }

    @Test
    public void testWithoutAnyHeaderCaptureUsingSetHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersUsingSetHeader.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getDetail()).doesNotContainKey("Response headers");
    }

    @Test
    public void testWithoutAnyHeaderCaptureUsingAddHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersUsingAddHeader.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getDetail()).doesNotContainKey("Response headers");
    }

    @Test
    public void testLotsOfResponseHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "One,Two,Date-One,Date-Two,Int-One,Int-Two,X-One");
        // when
        container.executeAppUnderTest(SetLotsOfResponseHeaders.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> responseHeaders =
                (Map<String, Object>) entry.getMessage().getDetail().get("Response headers");
        @SuppressWarnings("unchecked")
        List<String> one = (List<String>) responseHeaders.get("One");
        @SuppressWarnings("unchecked")
        List<String> dOne = (List<String>) responseHeaders.get("Date-One");
        @SuppressWarnings("unchecked")
        List<String> iOne = (List<String>) responseHeaders.get("Int-One");
        @SuppressWarnings("unchecked")
        List<String> xOne = (List<String>) responseHeaders.get("X-One");
        assertThat(one).containsExactly("ab", "xy");
        assertThat(responseHeaders.get("Two")).isEqualTo("1");
        assertThat(responseHeaders.get("Three")).isNull();
        assertThat(dOne).containsExactly("Fri, 28 Feb 2014 02:06:52 GMT",
                "Fri, 28 Feb 2014 02:06:53 GMT");
        assertThat(responseHeaders.get("Date-Two")).isEqualTo("Fri, 28 Feb 2014 02:06:54 GMT");
        assertThat(responseHeaders.get("Date-Three")).isNull();
        assertThat(iOne).containsExactly("2", "3");
        assertThat(responseHeaders.get("Int-Two")).isEqualTo("4");
        assertThat(responseHeaders.get("Int-Three")).isNull();
        assertThat(xOne).containsExactly("xy", "Fri, 28 Feb 2014 02:06:56 GMT", "6");
    }

    @Test
    public void testOutsideServlet() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");
        // when
        container.executeAppUnderTest(SetStandardResponseHeadersOutsideServlet.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace).isNull();
        // basically just testing that it should not generate any errors
    }

    @SuppressWarnings("serial")
    public static class SetStandardResponseHeaders extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setContentLength(1);
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            response.setLocale(Locale.ENGLISH);
        }
    }

    @SuppressWarnings("serial")
    public static class SetStandardResponseHeadersUsingSetHeader extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setHeader("Content-Type", "text/plain;charset=UTF-8");
            response.setHeader("Content-Length", "1");
            response.setHeader("Extra", "abc");
            response.setLocale(Locale.ENGLISH);
        }
    }

    @SuppressWarnings("serial")
    public static class SetStandardResponseHeadersUsingAddHeader extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.addHeader("Content-Type", "text/plain;charset=UTF-8");
            response.addHeader("Content-Length", "1");
            response.addHeader("Extra", "abc");
            response.setLocale(Locale.ENGLISH);
        }
    }

    @SuppressWarnings("serial")
    public static class SetStandardResponseHeadersLowercase extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setHeader("content-type", "text/plain;charset=UTF-8");
            response.setHeader("content-length", "1");
            response.setHeader("extra", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetLotsOfResponseHeaders extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.setHeader("One", "abc");
            response.setHeader("one", "ab");
            response.addHeader("one", "xy");
            response.setHeader("Two", "1");
            response.setHeader("Three", "xyz");

            response.setDateHeader("Date-One", 1393553211832L);
            response.setDateHeader("Date-one", 1393553212832L);
            response.addDateHeader("Date-one", 1393553213832L);
            response.setDateHeader("Date-Two", 1393553214832L);
            response.setDateHeader("Date-Thr", 1393553215832L);
            response.addDateHeader("Date-Four", 1393553215832L);

            response.setIntHeader("Int-One", 1);
            response.setIntHeader("Int-one", 2);
            response.addIntHeader("Int-one", 3);
            response.setIntHeader("Int-Two", 4);
            response.setIntHeader("Int-Thr", 5);
            response.addIntHeader("Int-Four", 6);

            response.addHeader("X-One", "xy");
            response.addDateHeader("X-one", 1393553216832L);
            response.addIntHeader("X-one", 6);
        }
    }

    public static class SetStandardResponseHeadersOutsideServlet implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            MockHttpServletResponse response = new PatchedMockHttpServletResponse();
            response.setContentLength(1);
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            response.setLocale(Locale.ENGLISH);
        }
    }
}
