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

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.trace.Trace;

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
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "testattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasSessionAttributeWithoutTrimmedAttributeName() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                " testattr , other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcardPlusOther() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "*,other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "testattr,testother");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getInitialSessionAttributes(header)).isNotNull();
        assertThat(getInitialSessionAttributes(header).get("testother")).isEqualTo("v");
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcardAndOther() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "*,other");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttributeNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header).containsValue("testattr")).isFalse();
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two.three,one.amap.x");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header).get("one.two.three")).isEqualTo("four");
        assertThat(getSessionAttributes(header).get("one.amap.x")).isEqualTo("y");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two.three,one.amap.x");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header).get("one.two.three")).isEqualTo("four");
        assertThat(getUpdatedSessionAttributes(header).get("one.amap.x")).isEqualTo("y");
    }

    @Test
    public void testHasMissingSessionAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "missingtestattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.missingtwo");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*,one.two.*,one.amap.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNotNull();
        assertThat(getSessionAttributes(header)).hasSize(5);
        assertThat(getSessionAttributes(header).get("one.two.three")).isEqualTo("four");
        assertThat(getSessionAttributes(header).get("one.amap.x")).isEqualTo("y");
        assertThat(getSessionAttributes(header).get("one.another")).isEqualTo("3");
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*,one.two.*,one.amap.*");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header)).hasSize(5);
        assertThat(getUpdatedSessionAttributes(header).get("one.two.three")).isEqualTo("four");
        assertThat(getUpdatedSessionAttributes(header).get("one.amap.x")).isEqualTo("y");
        assertThat(getUpdatedSessionAttributes(header).get("one.another")).isEqualTo("3");
    }

    @Test
    public void testSetNestedSessionAttributeToNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*");
        // when
        container.executeAppUnderTest(SetNestedSessionAttributeToNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header)).hasSize(1);
        assertThat(getUpdatedSessionAttributes(header).containsKey("one")).isTrue();
        assertThat(getUpdatedSessionAttributes(header).get("one")).isNull();
    }

    @Test
    public void testSetSessionAttributeToNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two");
        // when
        container.executeAppUnderTest(SetNestedSessionAttributeToNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNotNull();
        assertThat(getUpdatedSessionAttributes(header)).hasSize(1);
        assertThat(getUpdatedSessionAttributes(header).containsKey("one.two")).isTrue();
        assertThat(getUpdatedSessionAttributes(header).get("one.two")).isNull();
    }

    @Test
    public void testHasMissingSessionAttribute2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "missingtestattr.*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.missingtwo.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @Test
    public void testGetBadAttributeNames() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(GetBadAttributeNames.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(getSessionAttributes(header)).isNull();
        assertThat(getUpdatedSessionAttributes(header)).isNull();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> getSessionAttributes(Trace.Header header) {
        return (Map<String, String>) header.detail().get("Session attributes");
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> getInitialSessionAttributes(Trace.Header header) {
        return (Map<String, String>) header.detail()
                .get("Session attributes (at beginning of this request)");
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> getUpdatedSessionAttributes(Trace.Header header) {
        return (Map<String, String>) header.detail()
                .get("Session attributes (updated during this request)");
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
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("testother", "v");
        }
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
            request.getSession().setAttribute("one", new NestedTwo("four", "3"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", new NestedTwo("four", "3"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionAttributeToNull extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("one", null);
        }
    }

    @SuppressWarnings("serial")
    public static class GetBadAttributeNames extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setSession(new MockHttpSession() {
                @Override
                public Enumeration<String> getAttributeNames() {
                    return Collections.enumeration(Lists.newArrayList((String) null));
                }
            });
        }
    }

    public static class NestedTwo {
        private final NestedThree two;
        private final StringBuilder another;
        private final String iamnull = null;
        private final Map<String, String> amap = ImmutableMap.of("x", "y");
        public NestedTwo(String two, String another) {
            this.two = new NestedThree(two);
            this.another = new StringBuilder(another);
        }
        public NestedThree getTwo() {
            return two;
        }
        public StringBuilder getAnother() {
            return another;
        }
        public String getIamnull() {
            return iamnull;
        }
        public Map<String, String> getAmap() {
            return amap;
        }
    }

    public static class NestedThree {
        private final String three;
        public NestedThree(String three) {
            this.three = three;
        }
        public String getThree() {
            return three;
        }
    }
}
