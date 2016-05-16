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
package org.glowroot.server.storage;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.storage.config.ImmutableRoleConfig;
import org.glowroot.storage.config.RoleConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class RoleDaoIT {

    private static Session session;
    private static RoleDao roleDao;

    @BeforeClass
    public static void setUp() throws Exception {
        CassandraWrapper.start();
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot");

        roleDao = new RoleDao(session, cluster.getMetadata().getKeyspace("glowroot"));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        CassandraWrapper.stop();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        roleDao.insert(ImmutableRoleConfig.builder()
                .name("abc")
                .addPermissions("*:*")
                .build());
        // when
        RoleConfig roleConfig = roleDao.read("abc");
        // then
        assertThat(roleConfig.name()).isEqualTo("abc");
        assertThat(roleConfig.permissions()).containsExactly("*:*");
    }
}
