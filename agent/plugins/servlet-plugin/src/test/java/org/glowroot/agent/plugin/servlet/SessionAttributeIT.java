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

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class SessionAttributeIT {

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
    public void testHasSessionAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "testattr");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeWithoutTrimmedAttributeName() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                " testattr , other");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeUsingWildcardPlusOther() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "*,other,::id");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("testattr")).isEqualTo("val");
        assertThat(getSessionAttributes(trace).get("::id")).isEqualTo("123456789");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "testattr,testother");
        // when
        Trace trace = container.execute(SetSessionAttribute.class, "Web");
        // then
        assertThat(getInitialSessionAttributes(trace)).isNotNull();
        assertThat(getInitialSessionAttributes(trace).get("testother")).isEqualTo("v");
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcard() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(SetSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeUsingWildcardAndOther() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "*,other");
        // when
        Trace trace = container.execute(SetSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("testattr")).isEqualTo("val");
    }

    @Test
    public void testSetSessionAttributeNotReadable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "");
        // when
        Trace trace = container.execute(SetSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetSessionAttributeNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(SetSessionAttributeNull.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).containsValue("testattr")).isFalse();
    }

    @Test
    public void testHasNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two.three,one.amap.x");
        // when
        Trace trace = container.execute(HasNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("one.two.three")).isEqualTo("four");
        assertThat(getSessionAttributes(trace).get("one.amap.x")).isEqualTo("y");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two.three,one.amap.x");
        // when
        Trace trace = container.execute(SetNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace).get("one.two.three")).isEqualTo("four");
        assertThat(getUpdatedSessionAttributes(trace).get("one.amap.x")).isEqualTo("y");
    }

    @Test
    public void testHasMissingSessionAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "missingtestattr");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.missingtwo");
        // when
        Trace trace = container.execute(HasNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*,one.two.*,one.amap.*");
        // when
        Trace trace = container.execute(HasNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace)).hasSize(5);
        assertThat(getSessionAttributes(trace).get("one.two.three")).isEqualTo("four");
        assertThat(getSessionAttributes(trace).get("one.amap.x")).isEqualTo("y");
        assertThat(getSessionAttributes(trace).get("one.another")).isEqualTo("3");
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testSetNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*,one.two.*,one.amap.*");
        // when
        Trace trace = container.execute(SetNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace)).hasSize(5);
        assertThat(getUpdatedSessionAttributes(trace).get("one.two.three")).isEqualTo("four");
        assertThat(getUpdatedSessionAttributes(trace).get("one.amap.x")).isEqualTo("y");
        assertThat(getUpdatedSessionAttributes(trace).get("one.another")).isEqualTo("3");
    }

    @Test
    public void testSetNestedSessionAttributeToNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.*");
        // when
        Trace trace = container.execute(SetNestedSessionAttributeToNull.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace)).hasSize(1);
        assertThat(getUpdatedSessionAttributes(trace).containsKey("one")).isTrue();
        assertThat(getUpdatedSessionAttributes(trace).get("one")).isNull();
    }

    @Test
    public void testSetSessionAttributeToNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.two");
        // when
        Trace trace = container.execute(SetNestedSessionAttributeToNull.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNotNull();
        assertThat(getUpdatedSessionAttributes(trace)).hasSize(1);
        assertThat(getUpdatedSessionAttributes(trace).containsKey("one.two")).isTrue();
        assertThat(getUpdatedSessionAttributes(trace).get("one.two")).isNull();
    }

    @Test
    public void testHasMissingSessionAttribute2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "missingtestattr.*");
        // when
        Trace trace = container.execute(HasSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasMissingNestedSessionAttributePath2() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "one.missingtwo.*");
        // when
        Trace trace = container.execute(HasNestedSessionAttribute.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testGetBadAttributeNames() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes", "*");
        // when
        Trace trace = container.execute(GetBadAttributeNames.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(HasHttpSession.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNotNull();
        assertThat(getSessionAttributes(trace).get("::id")).isEqualTo("123456789");
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testHasNoHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(HasNoHttpSession.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testCreateHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(CreateHttpSession.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace).get("::id")).isEqualTo("123456789");
    }

    @Test
    public void testCreateHttpSessionTrue() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(CreateHttpSessionTrue.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace).get("::id")).isEqualTo("123456789");
    }

    @Test
    public void testCreateHttpSessionFalse() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(CreateHttpSessionFalse.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace)).isNull();
    }

    @Test
    public void testChangeHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(ChangeHttpSession.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace).get("::id")).isEqualTo("123456789");
        assertThat(getUpdatedSessionAttributes(trace).get("::id")).isEqualTo("abcdef");
    }

    @Test
    public void testCreateAndChangeHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureSessionAttributes",
                "::id");
        // when
        Trace trace = container.execute(CreateAndChangeHttpSession.class, "Web");
        // then
        assertThat(getSessionAttributes(trace)).isNull();
        assertThat(getInitialSessionAttributes(trace)).isNull();
        assertThat(getUpdatedSessionAttributes(trace).get("::id")).isEqualTo("abcdef");
    }

    static @Nullable Map<String, String> getSessionAttributes(Trace trace) {
        return getDetailMap(trace, "Session attributes");
    }

    static @Nullable Map<String, String> getInitialSessionAttributes(Trace trace) {
        return getDetailMap(trace, "Session attributes (at beginning of this request)");
    }

    static @Nullable Map<String, String> getUpdatedSessionAttributes(Trace trace) {
        return getDetailMap(trace, "Session attributes (updated during this request)");
    }

    private static @Nullable Map<String, String> getDetailMap(Trace trace, String name) {
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
        Map<String, String> responseHeaders = Maps.newLinkedHashMap();
        for (Trace.DetailEntry detail : found.getChildEntryList()) {
            if (detail.getValueList().isEmpty()) {
                responseHeaders.put(detail.getName(), null);
            } else {
                responseHeaders.put(detail.getName(), detail.getValueList().get(0).getString());
            }
        }
        return responseHeaders;
    }

    @SuppressWarnings("serial")
    public static class HasSessionAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
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

    @SuppressWarnings("serial")
    public static class HasHttpSession extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNoHttpSession extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {}
    }

    @SuppressWarnings("serial")
    public static class CreateHttpSession extends TestServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
            request.getSession();
        }
    }

    @SuppressWarnings("serial")
    public static class CreateHttpSessionTrue extends TestServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
            request.getSession(true);
            super.service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class CreateHttpSessionFalse extends TestServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getSession(false);
            super.service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class ChangeHttpSession extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
        }
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getSession().invalidate();
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "abcdef");
            ((MockHttpServletRequest) request).setSession(session);
            request.getSession();
            super.service(request, response);
        }
    }

    @SuppressWarnings("serial")
    public static class CreateAndChangeHttpSession extends TestServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            MockHttpSession session = new MockHttpSession(request.getServletContext(), "123456789");
            ((MockHttpServletRequest) request).setSession(session);
            request.getSession().invalidate();
            session = new MockHttpSession(request.getServletContext(), "abcdef");
            ((MockHttpServletRequest) request).setSession(session);
            request.getSession();
            super.service(request, response);
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
