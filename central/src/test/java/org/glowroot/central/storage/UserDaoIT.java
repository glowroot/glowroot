/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.storage;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.UserConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class UserDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static UserDao userDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        userDao = new UserDao(session, keyspace);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        userDao.insert(ImmutableUserConfig.builder()
                .username("abc")
                .passwordHash("xyz")
                .addRoles("arole", "brole")
                .build());

        // when
        UserConfig userConfig = userDao.read("abc");

        // then
        assertThat(userConfig.username()).isEqualTo("abc");
        assertThat(userConfig.passwordHash()).isEqualTo("xyz");
        assertThat(userConfig.roles()).containsExactly("arole", "brole");
    }
}
