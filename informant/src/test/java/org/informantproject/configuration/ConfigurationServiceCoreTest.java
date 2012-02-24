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
import static org.junit.Assert.assertThat;

import java.sql.SQLException;
import java.util.Set;

import org.informantproject.util.DataSource;
import org.informantproject.util.DataSourceTestProvider;
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
public class ConfigurationServiceCoreTest {

    private Set<Thread> preExistingThreads;

    public static class Module extends JukitoModule {
        @Override
        protected void configureTest() {
            bind(ConfigurationService.class).in(TestSingleton.class);
            bind(DataSource.class).toProvider(DataSourceTestProvider.class).in(TestSingleton.class);
        }
    }

    @Before
    public void before() {
        preExistingThreads = ThreadChecker.currentThreadList();
    }

    @After
    public void after(DataSource dataSource) throws SQLException, InterruptedException {
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        dataSource.close();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldReturnDefaultCoreConfiguration(ConfigurationService configurationService) {
        // given (no prerequisites)
        // when
        ImmutableCoreConfiguration coreConfiguration = configurationService.getCoreConfiguration();
        // then
        assertThat(coreConfiguration, is(new ImmutableCoreConfiguration()));
    }

    @Test
    public void shouldUpdateCoreConfiguration(ConfigurationService configurationService) {

        // given
        ImmutableCoreConfiguration randomCoreConfiguration = new CoreConfigurationTestData()
                .getRandomCoreConfiguration();
        // when
        configurationService.updateCoreConfiguration(randomCoreConfiguration.toJson());
        // then
        assertThat(configurationService.getCoreConfiguration(), is(randomCoreConfiguration));
    }
}
