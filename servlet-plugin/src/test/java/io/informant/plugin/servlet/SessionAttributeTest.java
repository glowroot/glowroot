/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.servlet;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import checkers.nullness.quals.Nullable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.testkit.Container;
import io.informant.testkit.Trace;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SessionAttributeTest {

    private static final String PLUGIN_ID = "io.informant.plugins:servlet-plugin";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Container.create();
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
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "testattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeWithoutTrimmedAttributeName() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", " testattr , other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcardPlusOther() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*,other");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "testattr");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcardAndOther() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*,other");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        container.executeAppUnderTest(SetSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        container.executeAppUnderTest(SetSessionAttributeNull.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).containsValue("testattr")).isFalse();
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.two");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("one.two")).isEqualTo("three");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.two");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("one.two")).isEqualTo("three");
    }

    @Test
    public void testHasMissingSessionAttribute() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "missingtestattr");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.missingtwo");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasNestedSessionAttributePath2() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace)).hasSize(2);
        assertThat(getSessionAttributes(trace).get("one.two")).isEqualTo("three");
        assertThat(getSessionAttributes(trace).get("one.another")).isEqualTo("3");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath2() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.*");
        // when
        container.executeAppUnderTest(SetNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace)).hasSize(2);
        assertThat(getUpdatedSessionAttributes(trace).get("one.two")).isEqualTo("three");
        assertThat(getUpdatedSessionAttributes(trace).get("one.another")).isEqualTo("3");
    }

    @Test
    public void testHasMissingSessionAttribute2() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "missingtestattr.*");
        // when
        container.executeAppUnderTest(HasSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath2() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "one.missingtwo.*");
        // when
        container.executeAppUnderTest(HasNestedSessionAttribute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getSessionAttributes(Trace trace) {
        Map<String, Object> detail = trace.getSpans().get(0).getMessage().getDetail();
        if (detail == null) {
            return null;
        } else {
            return (Map<String, String>) detail.get("session attributes");
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Map<String, String> getUpdatedSessionAttributes(Trace trace) {
        Map<String, Object> detail = trace.getSpans().get(0).getMessage().getDetail();
        if (detail == null) {
            return null;
        } else {
            return (Map<String, String>) detail
                    .get("session attributes (updated during this request)");
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
