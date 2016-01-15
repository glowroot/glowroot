/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.fat.storage.util.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TriggeredAlertDaoTest {

    private DataSource dataSource;
    private TriggeredAlertDao triggeredAlertDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = new DataSource();
        if (dataSource.tableExists("triggered_alert")) {
            dataSource.execute("drop table triggered_alert");
        }
        triggeredAlertDao = new TriggeredAlertDao(dataSource);
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
    }

    @Test
    public void shouldNotExist() throws Exception {
        assertThat(triggeredAlertDao.exists("vvv1")).isFalse();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        triggeredAlertDao.insert("vvv2", 1234);
        assertThat(triggeredAlertDao.exists("vvv1")).isFalse();
        assertThat(triggeredAlertDao.exists("vvv2")).isTrue();
    }

    @Test
    public void shouldNotExistAfterDelete() throws Exception {
        triggeredAlertDao.insert("vvv3", 1234);
        triggeredAlertDao.delete("vvv3");
        assertThat(triggeredAlertDao.exists("vvv3")).isFalse();
    }
}
