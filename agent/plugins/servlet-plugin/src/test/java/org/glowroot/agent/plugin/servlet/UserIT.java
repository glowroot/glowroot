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
import java.security.Principal;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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

public class UserIT {

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
    public void testHasRequestUserPrincipal() throws Exception {
        // when
        Trace trace = container.execute(HasRequestUserPrincipal.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("my name is mock");
    }

    @Test
    public void testHasRequestWithExceptionOnGetUserPrincipal() throws Exception {
        // when
        container.execute(HasRequestWithExceptionOnGetUserPrincipal.class, "Web");
        // then don't blow up
    }

    @Test
    public void testHasSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        Trace trace = container.execute(HasSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        Trace trace = container.execute(SetSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserAttributeNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        Trace trace = container.execute(SetSessionUserAttributeNull.class, "Web");
        // then
        // this is intentional, setting user attribute to null shouldn't clear out user for
        // that particular request (since the request was in fact, originally, for that user)
        assertThat(trace.getHeader().getUser()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.two");
        // when
        Trace trace = container.execute(HasNestedSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.two");
        // when
        Trace trace = container.execute(SetNestedSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "missinguserattr");
        // when
        Trace trace = container.execute(HasSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEmpty();
    }

    @Test
    public void testHasMissingNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.missingtwo");
        // when
        Trace trace = container.execute(HasNestedSessionUserAttribute.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEmpty();
    }

    @Test
    public void testHasHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(HasHttpSession.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("123456789");
    }

    @Test
    public void testHasNoHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(HasNoHttpSession.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEmpty();
    }

    @Test
    public void testCreateHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(CreateHttpSession.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("123456789");
    }

    @Test
    public void testCreateHttpSessionTrue() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(CreateHttpSessionTrue.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("123456789");
    }

    @Test
    public void testCreateHttpSessionFalse() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(CreateHttpSessionFalse.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEmpty();
    }

    @Test
    public void testChangeHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(ChangeHttpSession.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("123456789");
    }

    @Test
    public void testCreateAndChangeHttpSession() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute", "::id");
        // when
        Trace trace = container.execute(CreateAndChangeHttpSession.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("123456789");
    }

    @SuppressWarnings("serial")
    public static class HasRequestUserPrincipal extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            ((MockHttpServletRequest) request).setUserPrincipal(new MockPrincipal());
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            // user principal is only captured if app actually uses it
            // (since it may throw exception)
            request.getUserPrincipal();
        }
    }

    @SuppressWarnings("serial")
    public static class HasRequestWithExceptionOnGetUserPrincipal extends TestServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            super.service(new UserPrincipalThrowingMHSR("GET", "/testservlet"), response);
        }
    }

    @SuppressWarnings("serial")
    public static class HasSessionUserAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userattr", "abc");
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

    @SuppressWarnings("serial")
    public static class SetSessionUserAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUserAttributeNull extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userattr", "something");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userattr", null);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNestedSessionUserAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionUserAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("userone", new NestedTwo("xyz"));
        }
    }

    public static class UserPrincipalThrowingMHSR extends MockCatalinaHttpServletRequest {
        public UserPrincipalThrowingMHSR(String method, String requestURI) {
            super(method, requestURI);
        }
        @Override
        public Principal getUserPrincipal() {
            throw new RuntimeException("Glowroot should not call this directly as it may fail");
        }
    }

    public static class NestedTwo {
        private final String two;
        public NestedTwo(String two) {
            this.two = two;
        }
        public String getTwo() {
            return two;
        }
    }

    private static class MockPrincipal implements Principal {
        @Override
        public String getName() {
            return "my name is mock";
        }
    }
}
