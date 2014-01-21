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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserIdTest {

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
    public void testHasSessionUserIdAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridattr");
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridattr");
        // when
        container.executeAppUnderTest(SetSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttributeNull() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridattr");
        // when
        container.executeAppUnderTest(SetSessionUserIdAttributeNull.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        // this is intentional, setting user id attribute to null shouldn't clear out user id for
        // that particular request (since the request was in fact, originally, for that user id)
        assertThat(trace.getUserId()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridone.two");
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridone.two");
        // when
        container.executeAppUnderTest(SetNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUserIdAttribute() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "missinguseridattr");
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isNull();
    }

    @Test
    public void testHasMissingNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getConfigService()
                .setPluginProperty(PLUGIN_ID, "sessionUserIdAttribute", "useridone.missingtwo");
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUserId()).isNull();
    }

    @SuppressWarnings("serial")
    public static class HasSessionUserIdAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUserIdAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "abc");
        }
    }

    @SuppressWarnings("serial")
    public static class SetSessionUserIdAttributeNull extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", "something");
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridattr", null);
        }
    }

    @SuppressWarnings("serial")
    public static class HasNestedSessionUserIdAttribute extends TestServlet {
        @Override
        protected void before(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridone", new NestedTwo("xyz"));
        }
    }

    @SuppressWarnings("serial")
    public static class SetNestedSessionUserIdAttribute extends TestServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            request.getSession().setAttribute("useridone", new NestedTwo("xyz"));
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
}
