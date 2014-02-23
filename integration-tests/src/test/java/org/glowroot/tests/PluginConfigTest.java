/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.tests;

import java.util.Random;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfigTest {

    private static final String PLUGIN_ID = "glowroot-integration-tests";

    private static final Random random = new Random();

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
    public void shouldUpdateAndReadBackPluginConfig() throws Exception {
        // given
        String randomText = "Level " + random.nextLong();
        boolean randomBoolean = random.nextBoolean();
        PluginConfig randomPluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        randomPluginConfig.setProperty("alternateHeadline", randomText);
        randomPluginConfig.setProperty("starredHeadline", randomBoolean);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, randomPluginConfig);
        // when
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        // then
        assertThat(pluginConfig.getProperty("alternateHeadline")).isEqualTo(randomText);
        assertThat(pluginConfig.getProperty("starredHeadline")).isEqualTo(randomBoolean);
    }

    @Test
    public void shouldReadDefaultPropertyValue() throws Exception {
        // when
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        // then
        assertThat((String) pluginConfig.getProperty("hasDefaultVal")).isEqualTo("one");
    }

    @Test
    public void shouldSetNullPropertyValueAsEmptyString() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        PluginConfig updatedPluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        // then
        assertThat(updatedPluginConfig.getProperty("alternateHeadline")).isEqualTo("");
        assertThat(updatedPluginConfig.hasProperty("alternateHeadline")).isTrue();
    }

    @Test
    public void shouldClearPluginProperty() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "a non-null value");
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // then
        pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        assertThat(pluginConfig.getProperty("alternateHeadline")).isEqualTo("");
    }

    @Test
    public void shouldReadAlternateHeadline() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "Level 1");
        pluginConfig.setProperty("starredHeadline", false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getTraceService().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level 1");
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
    }

    @Test
    public void shouldReadStarredHeadline() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        pluginConfig.setProperty("starredHeadline", true);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getTraceService().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level One*");
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
    }

    public static class SimpleApp implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }
}
