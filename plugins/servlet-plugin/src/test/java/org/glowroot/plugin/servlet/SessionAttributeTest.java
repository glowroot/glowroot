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

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class SessionAttributeTest {

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
    public void testHasSessionAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "testattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasSessionAttributeWithoutTrimmedAttributeName() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", " testattr , other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcardPlusOther() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*,other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "testattr");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcardAndOther() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*,other");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttributeNull.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries).containsValue("testattr")).isFalse();
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.two");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries).get("one.two")).isEqualTo("three");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.two");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries).get("one.two")).isEqualTo("three");
    }

    @Test
    public void testHasMissingSessionAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "missingtestattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.missingtwo");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNotNull();
        assertThat(getSessionAttributes(entries)).hasSize(2);
        assertThat(getSessionAttributes(entries).get("one.two")).isEqualTo("three");
        assertThat(getSessionAttributes(entries).get("one.another")).isEqualTo("3");
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.*");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNotNull();
        assertThat(getUpdatedSessionAttributes(entries)).hasSize(2);
        assertThat(getUpdatedSessionAttributes(entries).get("one.two")).isEqualTo("three");
        assertThat(getUpdatedSessionAttributes(entries).get("one.another")).isEqualTo("3");
    }

    @Test
    public void testHasMissingSessionAttribute2() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "missingtestattr.*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.missingtwo.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(getSessionAttributes(entries)).isNull();
        assertThat(getUpdatedSessionAttributes(entries)).isNull();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getSessionAttributes(List<TraceEntry> entries) {
        Map<String, Object> detail = entries.get(0).getMessage().getDetail();
        if (detail == null) {
            return null;
        } else {
            return (Map<String, String>) detail.get("Session attributes");
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getUpdatedSessionAttributes(List<TraceEntry> entries) {
        Map<String, Object> detail = entries.get(0).getMessage().getDetail();
        if (detail == null) {
            return null;
        } else {
            return (Map<String, String>) detail
                    .get("Session attributes (updated during this request)");
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testattr", "val");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionAttributeNull extends TestServlet {
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
    public static class HasNestedSessionAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three", "3"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("three", "3"));
        }
    }

    public static class NestedTwo {
        private final String two;
        private final StringBuilder another;
        private final String iamnull = null;
        public NestedTwo(String two, String another) {
            this.two = two;
            this.another = new StringBuilder(another);
        }
        public String getTwo() {
            return two;
        }
        public StringBuilder getAnother() {
            return another;
        }
        public String getIamnull() {
            return iamnull;
        }
    }
}
