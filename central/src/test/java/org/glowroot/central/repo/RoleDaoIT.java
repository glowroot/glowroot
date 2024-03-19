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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.CassandraContainer;

import static org.assertj.core.api.Assertions.assertThat;


public class RoleDaoIT {

    public static final CassandraContainer cassandra
            = (CassandraContainer) new CassandraContainer("cassandra:3.11.16").withExposedPorts(9042);
    private static final int MAX_CONCURRENT_REQUESTS = 1024;

    private static CqlSessionBuilder cqlSessionBuilder;
    private static Session session;
    private static ClusterManager clusterManager;
    private static RoleDao roleDao;

    @BeforeAll
    public static void beforeClass() {
        cassandra.start();
    }

    @AfterAll
    public static void afterClass() {
        cassandra.stop();
    }

    @BeforeEach
    public void setUp() throws Exception {
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null,
                MAX_CONCURRENT_REQUESTS, 0);
        clusterManager = ClusterManager.create();
        roleDao = new RoleDao(session, clusterManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        try (var se = session;
             var cm = clusterManager) {
        }
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        roleDao.insert(ImmutableRoleConfig.builder()
                .central(true)
                .name("abc")
                .addPermissions("*:*")
                .build(), CassandraProfile.web).toCompletableFuture().join();
        // when
        RoleConfig roleConfig = roleDao.read("abc").toCompletableFuture().join();
        // then
        assertThat(roleConfig.name()).isEqualTo("abc");
        assertThat(roleConfig.permissions()).containsExactly("*:*");
    }
}
