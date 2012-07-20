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
package org.informantproject.core.configuration;

import static org.fest.assertions.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Set;

import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSourceTestProvider;
import org.informantproject.core.util.Threads;
import org.jukito.JukitoModule;
import org.jukito.JukitoRunner;
import org.jukito.TestSingleton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(JukitoRunner.class)
public class ConfigurationDaoCoreTest {

    private Set<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(DataSource.class).toProvider(DataSourceTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before(DataSource dataSource) throws SQLException {
        preExistingThreads = Threads.currentThreadList();
        if (dataSource.tableExists("configuration")) {
            dataSource.execute("drop table configuration");
        }
    }

    @After
    public void after(DataSource dataSource) throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.closeAndDeleteFile();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadEmptyConfiguration(ConfigurationDao configurationDao) {
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration).isNull();
    }

    @Test
    public void shouldReadConfiguration(ConfigurationDao configurationDao) {
        // given
        ImmutableCoreConfiguration defaultCoreConfiguration = new ImmutableCoreConfiguration();
        configurationDao.setCoreEnabled(defaultCoreConfiguration.isEnabled());
        configurationDao.storeCoreProperties(defaultCoreConfiguration.getPropertiesJson());
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration).isEqualTo(defaultCoreConfiguration);
    }

    @Test
    public void shouldReadAfterUpdatingPropertiesOnly(ConfigurationDao configurationDao) {
        // given
        ImmutableCoreConfiguration defaultCoreConfiguration = new ImmutableCoreConfiguration();
        configurationDao.storeCoreProperties(defaultCoreConfiguration.getPropertiesJson());
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration).isEqualTo(defaultCoreConfiguration);
    }

    @Test
    public void shouldReadAfterUpdatingEnabledOnly(ConfigurationDao configurationDao) {
        // given
        configurationDao.setCoreEnabled(false);
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration).isEqualTo(
                new CoreConfigurationBuilder().setEnabled(false).build());
    }

    @Test
    public void shouldUpdateConfiguration(ConfigurationDao configurationDao) {
        // given
        ImmutableCoreConfiguration defaultCoreConfiguration = new ImmutableCoreConfiguration();
        ImmutableCoreConfiguration randomCoreConfiguration = new CoreConfigurationTestData()
                .getRandomCoreConfiguration();
        configurationDao.storeCoreProperties(defaultCoreConfiguration.getPropertiesJson());
        configurationDao.setCoreEnabled(defaultCoreConfiguration.isEnabled());
        // when
        configurationDao.storeCoreProperties(randomCoreConfiguration.getPropertiesJson());
        configurationDao.setCoreEnabled(randomCoreConfiguration.isEnabled());
        // then
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        assertThat(coreConfiguration).isEqualTo(randomCoreConfiguration);
    }
}
