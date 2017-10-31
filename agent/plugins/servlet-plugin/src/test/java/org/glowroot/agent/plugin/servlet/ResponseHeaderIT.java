/*
 * Copyright 2011-2017 the original author or authors.
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
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.plugin.servlet.TestServlet.PatchedMockHttpServletResponse;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ResponseHeaderIT {

    private static final String PLUGIN_ID = "servlet";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
        Trace trace = container.execute(SetStandardResponseHeaders.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
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
        Trace trace = container.execute(SetStandardResponseHeadersUsingSetHeader.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
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
        Trace trace = container.execute(SetStandardResponseHeadersUsingAddHeader.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
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
        Trace trace = container.execute(SetStandardResponseHeadersLowercase.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
        assertThat(responseHeaders.get("content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("extra")).isNull();
    }

    @Test
    public void testWithoutAnyHeaderCapture() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        Trace trace = container.execute(SetStandardResponseHeaders.class, "Web");
        // then
        assertThat(getResponseHeaders(trace)).isNull();
    }

    @Test
    public void testWithoutAnyInterestingHeaderCapture() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "ABC");
        // when
        Trace trace = container.execute(SetStandardResponseHeaders.class, "Web");
        // then
        assertThat(getResponseHeaders(trace)).isNull();
    }

    @Test
    public void testWithoutAnyHeaderCaptureUsingSetHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        Trace trace = container.execute(SetStandardResponseHeadersUsingSetHeader.class, "Web");
        // then
        assertThat(getResponseHeaders(trace)).isNull();
    }

    @Test
    public void testWithoutAnyHeaderCaptureUsingAddHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders", "");
        // when
        Trace trace = container.execute(SetStandardResponseHeadersUsingAddHeader.class, "Web");
        // then
        assertThat(getResponseHeaders(trace)).isNull();
    }

    @Test
    public void testLotsOfResponseHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "One,Two,Date-One,Date-Two,Int-One,Int-Two,X-One");

        // when
        Trace trace = container.execute(SetLotsOfResponseHeaders.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
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
        container.executeNoExpectedTrace(SetStandardResponseHeadersOutsideServlet.class);
        // then
        // basically just testing that it should not generate any errors
    }

    static @Nullable Map<String, Object> getDetailMap(Trace trace, String name) {
        Trace.DetailEntry detailEntry = getDetailEntry(trace, name);
        if (detailEntry == null) {
            return null;
        }
        Map<String, Object> responseHeaders = Maps.newLinkedHashMap();
        for (Trace.DetailEntry detail : detailEntry.getChildEntryList()) {
            List<Trace.DetailValue> values = detail.getValueList();
            if (values.size() == 1) {
                responseHeaders.put(detail.getName(), values.get(0).getString());
            } else {
                List<String> vals = Lists.newArrayList();
                for (Trace.DetailValue value : values) {
                    vals.add(value.getString());
                }
                responseHeaders.put(detail.getName(), vals);
            }
        }
        return responseHeaders;
    }

    static @Nullable String getDetailValue(Trace trace, String name) {
        Trace.DetailEntry detailEntry = getDetailEntry(trace, name);
        return detailEntry == null ? null : detailEntry.getValue(0).getString();
    }

    private static @Nullable Map<String, Object> getResponseHeaders(Trace trace) {
        return getDetailMap(trace, "Response headers");
    }

    private static Trace.DetailEntry getDetailEntry(Trace trace, String name) {
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        Trace.DetailEntry found = null;
        for (Trace.DetailEntry detail : details) {
            if (detail.getName().equals(name)) {
                found = detail;
                break;
            }
        }
        return found;
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
