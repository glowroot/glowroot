/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.test;

import java.util.Random;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.config.PluginConfig;
import io.informant.container.trace.Trace;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfigTest {

    private static final String PLUGIN_ID = "io.informant:informant-integration-tests";

    private static final Random random = new Random();

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
        container.getConfigService().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "Level 1");
        pluginConfig.setProperty("starredHeadline", false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getTraceService().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level 1");
    }

    @Test
    public void shouldReadStarredHeadline() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        pluginConfig.setProperty("starredHeadline", true);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getTraceService().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level One*");
    }

    public static class SimpleApp implements AppUnderTest {
        public void executeApp() throws Exception {
            new LevelOne().call("a", "b");
        }
    }
}
