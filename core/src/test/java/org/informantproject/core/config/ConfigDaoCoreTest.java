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
package org.informantproject.core.config;

import static org.fest.assertions.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Collection;

import org.informantproject.core.config.CoreConfig.CoreConfigBuilder;
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
public class ConfigDaoCoreTest {

    private Collection<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(DataSource.class).toProvider(DataSourceTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before(DataSource dataSource) throws SQLException {
        preExistingThreads = Threads.currentThreads();
        if (dataSource.tableExists("config")) {
            dataSource.execute("drop table config");
        }
    }

    @After
    public void after(DataSource dataSource) throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.closeAndDeleteFile();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadEmptyConfig(ConfigDao configDao) {
        // when
        CoreConfig coreConfig = configDao.readCoreConfig();
        // then
        assertThat(coreConfig).isNull();
    }

    @Test
    public void shouldReadConfig(ConfigDao configDao) {
        // given
        CoreConfig defaultCoreConfig = new CoreConfig();
        configDao.setCoreEnabled(defaultCoreConfig.isEnabled());
        configDao.storeCoreProperties(defaultCoreConfig.getPropertiesJson());
        // when
        CoreConfig coreConfig = configDao.readCoreConfig();
        // then
        assertThat(coreConfig).isEqualTo(defaultCoreConfig);
    }

    @Test
    public void shouldReadAfterUpdatingPropertiesOnly(ConfigDao configDao) {
        // given
        CoreConfig defaultCoreConfig = new CoreConfig();
        configDao.storeCoreProperties(defaultCoreConfig.getPropertiesJson());
        // when
        CoreConfig coreConfig = configDao.readCoreConfig();
        // then
        assertThat(coreConfig).isEqualTo(defaultCoreConfig);
    }

    @Test
    public void shouldReadAfterUpdatingEnabledOnly(ConfigDao configDao) {
        // given
        configDao.setCoreEnabled(false);
        // when
        CoreConfig coreConfig = configDao.readCoreConfig();
        // then
        assertThat(coreConfig).isEqualTo(new CoreConfigBuilder().setEnabled(false).build());
    }

    @Test
    public void shouldUpdateConfig(ConfigDao configDao) {
        // given
        CoreConfig defaultCoreConfig = new CoreConfig();
        CoreConfig randomCoreConfig = new CoreConfigTestData().getRandomCoreConfig();
        configDao.storeCoreProperties(defaultCoreConfig.getPropertiesJson());
        configDao.setCoreEnabled(defaultCoreConfig.isEnabled());
        // when
        configDao.storeCoreProperties(randomCoreConfig.getPropertiesJson());
        configDao.setCoreEnabled(randomCoreConfig.isEnabled());
        // then
        CoreConfig coreConfig = configDao.readCoreConfig();
        assertThat(coreConfig).isEqualTo(randomCoreConfig);
    }
}
