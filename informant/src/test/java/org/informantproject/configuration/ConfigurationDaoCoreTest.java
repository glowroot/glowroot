/**
 * Copyright 2011 the original author or authors.
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
import java.util.Set;

import org.jukito.JukitoModule;
import org.jukito.JukitoRunner;
import org.jukito.TestSingleton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.informantproject.util.ConnectionTestProvider;
import org.informantproject.util.JdbcHelper;
import org.informantproject.util.ThreadChecker;

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
            bind(Connection.class).toProvider(ConnectionTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before(JdbcHelper jdbcHelper, Connection connection) throws SQLException {
        preExistingThreads = ThreadChecker.currentThreadList();
        if (jdbcHelper.tableExists("configuration")) {
            Statement statement = connection.createStatement();
            statement.execute("drop table configuration");
            statement.close();
        }
    }

    @After
    public void after(Connection connection) throws Exception {
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        connection.close();
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
        configurationDao.storeCoreConfiguration(originalCoreConfiguration);
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
        configurationDao.storeCoreConfiguration(defaultCoreConfiguration);
        // when
        configurationDao.storeCoreConfiguration(randomCoreConfiguration);
        // then
        ImmutableCoreConfiguration coreConfiguration = configurationDao.readCoreConfiguration();
        assertThat(coreConfiguration, is(randomCoreConfiguration));
    }
}
