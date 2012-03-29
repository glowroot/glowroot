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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.sql.SQLException;
import java.util.Set;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSourceTestProvider;
import org.informantproject.core.util.ThreadChecker;
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
        preExistingThreads = ThreadChecker.currentThreadList();
        if (dataSource.tableExists("configuration")) {
            dataSource.execute("drop table configuration");
        }
    }

    @After
    public void after(DataSource dataSource) throws Exception {
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        dataSource.close();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldReadEmptyConfiguration(ConfigurationDao configurationDao) {
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration, nullValue());
    }

    @Test
    public void shouldReadConfiguration(ConfigurationDao configurationDao) {
        // given
        ImmutableCoreConfiguration originalCoreConfiguration = new ImmutableCoreConfiguration();
        configurationDao.setCoreEnabled(originalCoreConfiguration.isEnabled());
        configurationDao.storeCoreProperties(originalCoreConfiguration.getPropertiesJson());
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        // then
        assertThat(coreConfiguration, is(originalCoreConfiguration));
    }

    @Test
    public void shouldUpdatedConfiguration(ConfigurationDao configurationDao) {
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
        assertThat(coreConfiguration, is(randomCoreConfiguration));
    }

    @Test
    public void shouldTestCoreEnabled(ConfigurationDao configurationDao) {
        // given
        ImmutableCoreConfiguration defaultCoreConfiguration = new ImmutableCoreConfiguration();
        configurationDao.storeCoreProperties(defaultCoreConfiguration.getPropertiesJson());
        // when
        ImmutableCoreConfiguration configuration = configurationDao.readCoreConfiguration();
        // then
        assertThat(configuration.isEnabled(), is(true));
    }
}
