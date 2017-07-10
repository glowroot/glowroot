/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.config.ImmutableRoleConfig;
import org.glowroot.common.config.RoleConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class RoleDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static RoleDao roleDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession());
        session.createKeyspaceIfNotExists("glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspaceMetadata =
                cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        clusterManager = ClusterManager.create();
        roleDao = new RoleDao(session, keyspaceMetadata, clusterManager);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        roleDao.insert(ImmutableRoleConfig.builder()
                .central(true)
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
