/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.plugin.javahttpserver;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sun.net.httpserver.HttpExchange;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("restriction")
public class ResponseHeaderIT {

    private static final String PLUGIN_ID = "java-http-server";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement com.sun.net.httpserver.HttpExchange
        container = Containers.createJavaagent();
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
    public void testStandardResponseHeadersUsingSetHeader() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");

        // when
        Trace trace = container.execute(SetStandardResponseHeadersUsingSetHeader.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
        assertThat(responseHeaders.get("Content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("Content-language")).isEqualTo("en");
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
        assertThat(responseHeaders.get("Content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("Content-language")).isEqualTo("en");
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
        assertThat(responseHeaders.get("Content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("Extra")).isNull();
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
        List<String> iOne = (List<String>) responseHeaders.get("Int-one");
        assertThat(one).containsExactly("ab", "xy");
        assertThat(responseHeaders.get("Two")).isEqualTo("1");
        assertThat(responseHeaders.get("Three")).isNull();
        assertThat(responseHeaders.get("Date-three")).isNull();
        assertThat(iOne).containsExactly("2", "3");
        assertThat(responseHeaders.get("Int-two")).isEqualTo("4");
        assertThat(responseHeaders.get("Int-three")).isNull();
    }

    @Test
    public void testOutsideHttpServer() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "Content-Type, Content-Length, Content-Language");
        // when
        container.executeNoExpectedTrace(SetStandardResponseHeadersOutsideHttpServer.class);
        // then
        // basically just testing that it should not generate any errors
    }

    @Test
    public void testDontMaskResponseHeaders() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResponseHeaders",
                "content*");
        container.getConfigService().setPluginProperty(PLUGIN_ID, "maskRequestHeaders",
                "content-Len*");

        // when
        Trace trace = container.execute(SetStandardResponseHeadersLowercase.class, "Web");

        // then
        Map<String, Object> responseHeaders = getResponseHeaders(trace);
        assertThat(responseHeaders.get("Content-type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(responseHeaders.get("Content-length")).isEqualTo("1");
        assertThat(responseHeaders.get("Extra")).isNull();
    }

    static @Nullable Map<String, Object> getDetailMap(Trace trace, String name) {
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        Trace.DetailEntry found = null;
        for (Trace.DetailEntry detail : details) {
            if (detail.getName().equals(name)) {
                found = detail;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        Map<String, Object> responseHeaders = Maps.newLinkedHashMap();
        for (Trace.DetailEntry detail : found.getChildEntryList()) {
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

    private static @Nullable Map<String, Object> getResponseHeaders(Trace trace) {
        return getDetailMap(trace, "Response headers");
    }

    public static class SetStandardResponseHeadersUsingSetHeader extends TestHandler {
        @Override
        public void handle(HttpExchange exchange) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Length", "1");
            exchange.getResponseHeaders().set("Extra", "abc");
            exchange.getResponseHeaders().set("Content-Language", Locale.ENGLISH.getLanguage());
        }
    }

    public static class SetStandardResponseHeadersUsingAddHeader extends TestHandler {
        @Override
        public void handle(HttpExchange exchange) {
            exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
            exchange.getResponseHeaders().add("Content-Length", "1");
            exchange.getResponseHeaders().add("Extra", "abc");
            exchange.getResponseHeaders().add("Content-Language", Locale.ENGLISH.getLanguage());
        }
    }

    public static class SetStandardResponseHeadersLowercase extends TestHandler {
        @Override
        public void handle(HttpExchange exchange) {
            exchange.getResponseHeaders().set("content-type", "text/plain;charset=UTF-8");
            exchange.getResponseHeaders().set("content-length", "1");
            exchange.getResponseHeaders().set("extra", "abc");
        }
    }

    public static class SetLotsOfResponseHeaders extends TestHandler {
        @Override
        public void handle(HttpExchange exchange) {
            exchange.getResponseHeaders().set("One", "abc");
            exchange.getResponseHeaders().set("one", "ab");
            exchange.getResponseHeaders().add("one", "xy");
            exchange.getResponseHeaders().set("Two", "1");
            exchange.getResponseHeaders().set("Three", "xyz");

            exchange.getResponseHeaders().set("Int-One", "1");
            exchange.getResponseHeaders().set("Int-one", "2");
            exchange.getResponseHeaders().add("Int-one", "3");
            exchange.getResponseHeaders().set("Int-Two", "4");
            exchange.getResponseHeaders().set("Int-Thr", "5");
            exchange.getResponseHeaders().add("Int-Four", "6");

            exchange.getResponseHeaders().add("X-One", "xy");
            exchange.getResponseHeaders().add("X-one", "6");
        }
    }

    public static class SetStandardResponseHeadersOutsideHttpServer implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            MockHttpExchange exchange = new MockHttpExchange();
            exchange.getResponseHeaders().set("Content-Length", "1");
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("Character-Encoding", "UTF-8");
            exchange.getResponseHeaders().set("Content-Language", Locale.ENGLISH.getLanguage());
        }
    }
}
