/**
 * Copyright 2011-2012 the original author or authors.
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

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.Config.PluginConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserIdTest {

    private static final String PLUGIN_ID = "io.informant.plugins:servlet-plugin";

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void testHasSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("abc");
    }

    @Test
    public void testSetSessionUserIdAttributeNull() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetSessionUserIdAttributeNull.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        // this is intentional, setting user id attribute to null shouldn't clear out user id for
        // that particular request (since the request was in fact, originally, for that user id)
        assertThat(trace.getUserId()).isEqualTo("something");
    }

    @Test
    public void testHasNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testSetNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.two");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SetNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isEqualTo("xyz");
    }

    @Test
    public void testHasMissingSessionUserIdAttribute() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "missinguseridattr");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getUserId()).isNull();
    }

    @Test
    public void testHasMissingNestedSessionUserIdAttributePath() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("sessionUserIdAttribute", "useridone.missingtwo");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(HasNestedSessionUserIdAttribute.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
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
