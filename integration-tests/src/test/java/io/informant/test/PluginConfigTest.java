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

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.PluginConfig;
import io.informant.testkit.Trace;

import java.util.Random;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfigTest {

    private static final String PLUGIN_ID = "io.informant:informant-integration-tests";

    private static final Random random = new Random();

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
    public void shouldUpdateAndReadBackPluginConfig() throws Exception {
        // given
        String randomText = "Level " + random.nextLong();
        boolean randomBoolean = random.nextBoolean();
        PluginConfig randomPluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        randomPluginConfig.setProperty("alternateHeadline", randomText);
        randomPluginConfig.setProperty("starredHeadline", randomBoolean);
        container.getInformant().updatePluginConfig(PLUGIN_ID, randomPluginConfig);
        // when
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        // then
        assertThat(pluginConfig.getProperty("alternateHeadline")).isEqualTo(randomText);
        assertThat(pluginConfig.getProperty("starredHeadline")).isEqualTo(randomBoolean);
    }

    @Test
    public void shouldReadDefaultPropertyValue() throws Exception {
        // when
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        // then
        assertThat((String) pluginConfig.getProperty("hasDefaultVal")).isEqualTo("one");
    }

    @Test
    public void shouldSetNullPropertyValueAsEmptyString() throws Exception {
        // given
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        PluginConfig updatedPluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        // then
        assertThat(updatedPluginConfig.getProperty("alternateHeadline")).isEqualTo("");
        assertThat(updatedPluginConfig.hasProperty("alternateHeadline")).isTrue();
    }

    @Test
    public void shouldClearPluginProperty() throws Exception {
        // given
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "a non-null value");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("alternateHeadline", "");
        container.getInformant().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // then
        pluginConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        assertThat(pluginConfig.getProperty("alternateHeadline")).isEqualTo("");
    }

    @Test
    public void shouldReadAlternateHeadline() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(
                "io.informant:informant-integration-tests");
        pluginConfig.setEnabled(true);
        pluginConfig.setProperty("alternateHeadline", "Level 1");
        pluginConfig.setProperty("starredHeadline", false);
        container.getInformant().updatePluginConfig("io.informant:informant-integration-tests",
                pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level 1");
    }

    @Test
    public void shouldReadStarredHeadline() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        PluginConfig pluginConfig = container.getInformant().getPluginConfig(
                "io.informant:informant-integration-tests");
        pluginConfig.setEnabled(true);
        pluginConfig.setProperty("alternateHeadline", "");
        pluginConfig.setProperty("starredHeadline", true);
        container.getInformant().updatePluginConfig("io.informant:informant-integration-tests",
                pluginConfig);
        // when
        container.executeAppUnderTest(SimpleApp.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getHeadline()).isEqualTo("Level One*");
    }

    public static class SimpleApp implements AppUnderTest {
        public void executeApp() throws Exception {
            new LevelOne().call("a", "b");
        }
    }
}
