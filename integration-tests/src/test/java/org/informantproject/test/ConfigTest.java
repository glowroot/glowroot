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
package org.informantproject.test;

import static org.fest.assertions.api.Assertions.assertThat;

import org.informantproject.testkit.Config.CoarseProfilingConfig;
import org.informantproject.testkit.Config.CoreConfig;
import org.informantproject.testkit.Config.FineProfilingConfig;
import org.informantproject.testkit.Config.PluginConfig;
import org.informantproject.testkit.InformantContainer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConfigTest {

    private static final String PLUGIN_ID = "org.informantproject:informant-integration-tests";

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.closeAndDeleteFiles();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraces();
    }

    @Test
    public void shouldDisableCoreConfigBeforeAnyUpdates() throws Exception {
        // when
        container.getInformant().disableCore();
        // then
        CoreConfig updatedConfig = container.getInformant().getCoreConfig();
        assertThat(updatedConfig.isEnabled()).isFalse();
    }

    @Test
    public void shouldDisableCoarseProfilingConfigBeforeAnyUpdates() throws Exception {
        // when
        container.getInformant().disableCoarseProfiling();
        // then
        CoarseProfilingConfig updatedConfig = container.getInformant().getCoarseProfilingConfig();
        assertThat(updatedConfig.isEnabled()).isFalse();
    }

    @Test
    public void shouldDisableFineProfilingConfigBeforeAnyUpdates() throws Exception {
        // when
        container.getInformant().disableFineProfiling();
        // then
        FineProfilingConfig updatedConfig = container.getInformant().getFineProfilingConfig();
        assertThat(updatedConfig.isEnabled()).isFalse();
    }

    @Test
    public void shouldUpdateCoreConfig() throws Exception {
        // given
        CoreConfig config = container.getInformant().getCoreConfig();
        // when
        config = updateAllFields(config);
        container.getInformant().updateCoreConfig(config);
        // then
        CoreConfig updatedConfig = container.getInformant().getCoreConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateCoarseProfilingConfig() throws Exception {
        // given
        CoarseProfilingConfig config = container.getInformant().getCoarseProfilingConfig();
        // when
        config = updateAllFields(config);
        container.getInformant().updateCoarseProfilingConfig(config);
        // then
        CoarseProfilingConfig updatedConfig = container.getInformant().getCoarseProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateFineProfilingConfig() throws Exception {
        // given
        FineProfilingConfig config = container.getInformant().getFineProfilingConfig();
        // when
        config = updateAllFields(config);
        container.getInformant().updateFineProfilingConfig(config);
        // then
        FineProfilingConfig updatedConfig = container.getInformant().getFineProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdatePluginConfig() throws Exception {
        // given
        PluginConfig config = container.getInformant().getPluginConfig(PLUGIN_ID);
        // when
        config = updateAllFields(config);
        container.getInformant().updatePluginConfig(PLUGIN_ID, config);
        // then
        PluginConfig updatedConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        assertThat(updatedConfig).isEqualTo(config);
    }

    private static CoreConfig updateAllFields(CoreConfig config) {
        CoreConfig updatedConfig = new CoreConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setPersistenceThresholdMillis(config.getPersistenceThresholdMillis() + 1);
        updatedConfig.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        updatedConfig.setMaxEntries(config.getMaxEntries() + 1);
        updatedConfig.setRollingSizeMb(config.getRollingSizeMb() + 1);
        updatedConfig.setWarnOnEntryOutsideTrace(!config.isWarnOnEntryOutsideTrace());
        updatedConfig.setMetricPeriodMillis(config.getMetricPeriodMillis() + 1);
        return updatedConfig;
    }

    private static CoarseProfilingConfig updateAllFields(CoarseProfilingConfig config) {
        CoarseProfilingConfig updatedConfig = new CoarseProfilingConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static FineProfilingConfig updateAllFields(FineProfilingConfig config) {
        FineProfilingConfig updatedConfig = new FineProfilingConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setTracePercentage(config.getTracePercentage() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static PluginConfig updateAllFields(PluginConfig config) {
        PluginConfig updatedConfig = new PluginConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        boolean starredDescription = (Boolean) config.getProperty("starredDescription");
        updatedConfig.setProperty("starredDescription", !starredDescription);
        String alternateDescription = (String) config.getProperty("alternateDescription");
        updatedConfig.setProperty("alternateDescription", alternateDescription + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        updatedConfig.setProperty("hasDefaultVal", hasDefaultVal + "x");
        return updatedConfig;
    }
}
