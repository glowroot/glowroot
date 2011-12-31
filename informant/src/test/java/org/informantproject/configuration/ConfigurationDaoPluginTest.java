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
package org.informantproject.configuration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.informantproject.util.ConnectionTestProvider;
import org.informantproject.util.JdbcUtil;
import org.informantproject.util.ThreadChecker;
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
public class ConfigurationDaoPluginTest {

    private Set<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(Connection.class).toProvider(ConnectionTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before(Connection connection) throws SQLException {
        preExistingThreads = ThreadChecker.currentThreadList();
        if (JdbcUtil.tableExists("configuration", connection)) {
            Statement statement = connection.createStatement();
            statement.execute("drop table configuration");
            statement.close();
        }
    }

    @After
    public void after(Connection connection) throws SQLException, InterruptedException {
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        connection.close();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldReadOnEmptyDatabaseAndReturnNull(ConfigurationDao configurationDao) {
        // when
        ImmutablePluginConfiguration pluginConfiguration = configurationDao
                .readPluginConfiguration();
        // then
        assertThat(pluginConfiguration, nullValue());
    }

    @Test
    public void shouldReadConfiguration(ConfigurationDao configurationDao) {
        // given
        ImmutablePluginConfiguration originalPluginConfiguration = new ImmutablePluginConfiguration(
                testConfigurationData());
        configurationDao.storePluginConfiguration(originalPluginConfiguration);
        // when
        ImmutablePluginConfiguration pluginConfiguration = configurationDao
                .readPluginConfiguration();
        // then
        assertThat(pluginConfiguration, is(originalPluginConfiguration));
    }

    @Test
    public void shouldUpdateConfiguration(ConfigurationDao configurationDao) {
        // given
        ImmutablePluginConfiguration originalPluginConfiguration = new ImmutablePluginConfiguration(
                testConfigurationData());
        configurationDao.storePluginConfiguration(originalPluginConfiguration);
        Map<String, Map<String, Object>> updatedConfigurationMap = testConfigurationData();
        updatedConfigurationMap.get("plugin1").put("key0", 0.0);
        updatedConfigurationMap.get("plugin1").put("key1", 1.1);
        ImmutablePluginConfiguration updatedPluginConfiguration = new ImmutablePluginConfiguration(
                updatedConfigurationMap);
        // when
        configurationDao.storePluginConfiguration(updatedPluginConfiguration);
        // then
        ImmutablePluginConfiguration pluginConfiguration = configurationDao
                .readPluginConfiguration();
        assertThat(pluginConfiguration, is(updatedPluginConfiguration));
    }

    private static Map<String, Map<String, Object>> testConfigurationData() {

        Map<String, Object> pluginMap1 = new HashMap<String, Object>();
        pluginMap1.put("key1", "value1");
        pluginMap1.put("key2", 2.0);

        Map<String, Object> pluginMap2 = new HashMap<String, Object>();
        pluginMap2.put("key1", "value1");
        pluginMap2.put("key2", 2.2);

        Map<String, Map<String, Object>> configurationMap =
                new HashMap<String, Map<String, Object>>();
        configurationMap.put("plugin1", pluginMap1);
        configurationMap.put("plugin2", pluginMap2);

        return configurationMap;
    }
}
