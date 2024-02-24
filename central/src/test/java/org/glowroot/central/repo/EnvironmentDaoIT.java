/*
 * Copyright 2016-2023 the original author or authors.
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
import org.glowroot.central.util.Session;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment.HostInfo;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.CassandraContainer;

import static org.assertj.core.api.Assertions.assertThat;

public class EnvironmentDaoIT {
    public static final CassandraContainer cassandra
            = (CassandraContainer) new CassandraContainer("cassandra:3.11.15").withExposedPorts(9042);

    private static CqlSessionBuilder cqlSessionBuilder;
    private static Session session;
    private static EnvironmentDao environmentDao;

    @BeforeAll
    public static void beforeClass() {
        cassandra.start();
    }

    @AfterAll
    public static void afterClass() {
        cassandra.stop();
    }

    @AfterEach
    public void tearDown() throws Exception {
        try (var se = session) {
        }
    }

    @BeforeEach
    public void before() throws Exception {
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null,
                0);

        environmentDao = new EnvironmentDao(session);
        session.updateSchemaWithRetry("truncate environment");
    }

    @Test
    public void shouldStoreEnvironment() throws Exception {
        // given
        Environment environment = Environment.newBuilder()
                .setHostInfo(HostInfo.newBuilder()
                        .setHostname("hosty"))
                .build();
        environmentDao.store("a", environment).toCompletableFuture().get();
        // when
        Environment readEnvironment = environmentDao.read("a", CassandraProfile.web);
        // then
        assertThat(readEnvironment).isEqualTo(environment);
    }
}
