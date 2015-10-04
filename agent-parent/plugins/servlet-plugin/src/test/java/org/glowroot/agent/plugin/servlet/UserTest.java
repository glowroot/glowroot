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

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class UserTest {

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
    public void testHasRequestUserPrincipal() throws Exception {
        // given
        // when
        container.executeAppUnderTest(HasRequestUserPrincipal.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("my name is mock");
    }

    @Test
    public void testHasRequestWithExceptionOnGetUserPrincipal() throws Exception {
        // given
        // when
        container.executeAppUnderTest(HasRequestWithExceptionOnGetUserPrincipal.class);
        // then don't blow up
    }

    @Test
    public void testHasSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        container.executeAppUnderTest(HasSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        container.executeAppUnderTest(SetSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserAttributeNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userattr");
        // when
        container.executeAppUnderTest(SetSessionUserAttributeNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        // this is intentional, setting user attribute to null shouldn't clear out user for
        // that particular request (since the request was in fact, originally, for that user)
        assertThat(header.user()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.two");
        // when
        container.executeAppUnderTest(HasNestedSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.two");
        // when
        container.executeAppUnderTest(SetNestedSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUserAttribute() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "missinguserattr");
        // when
        container.executeAppUnderTest(HasSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEmpty();
    }

    @Test
    public void testHasMissingNestedSessionUserAttributePath() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "sessionUserAttribute",
                "userone.missingtwo");
        // when
        container.executeAppUnderTest(HasNestedSessionUserAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEmpty();
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
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            MockHttpServletRequest request = new UserPrincipalThrowingMHSR("GET", "/testservlet");
            super.service(request, resp);
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
