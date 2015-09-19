/*
 * Copyright 2012-2015 the original author or authors.
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

import java.io.File;
import java.util.Random;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.config.PluginConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginConfigTest {

    private static final String PLUGIN_ID = "glowroot-integration-tests";

    private static final Random random = new Random();

    private static File baseDir;
    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // create config.json with empty properties to test different code path
        baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        Files.write("{\"ui\":{\"port\":0}}", new File(baseDir, "config.json"), Charsets.UTF_8);
        container = Containers.createWithFileDb(baseDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(baseDir);
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
        assertThat((Double) pluginConfig.getProperty("anumberWithDefaultValue")).isEqualTo(22);
        assertThat((Double) pluginConfig.getProperty("anumber")).isNull();
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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level 1");
        assertThat(header.transactionName()).isEqualTo("basic test");
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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One*");
        assertThat(header.transactionName()).isEqualTo("basic test");
    }

    public static class SimpleApp implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }
}
