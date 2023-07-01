/*
 * Copyright 2016-2018 the original author or authors.
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

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.glowroot.central.repo.CqlSessionBuilders.MAX_CONCURRENT_QUERIES;

public class RoleDaoIT {

    private static CqlSessionBuilder cqlSessionBuilder;
    private static Session session;
    private static ClusterManager clusterManager;
    private static RoleDao roleDao;

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cqlSessionBuilder = CqlSessionBuilders.newCqlSessionBuilder();
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null,
                MAX_CONCURRENT_QUERIES, 0);
        clusterManager = ClusterManager.create();
        roleDao = new RoleDao(session, clusterManager);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (!SharedSetupRunListener.isStarted()) {
            return;
        }
        try (var se = session;
             var cm = clusterManager) {
        } finally {
            SharedSetupRunListener.stopCassandra();
        }
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
