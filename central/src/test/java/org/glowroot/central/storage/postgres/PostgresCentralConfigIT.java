/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.util.concurrent.ExecutorService;

import com.zaxxer.hikari.HikariDataSource;
import org.glowroot.common2.config.ImmutableCentralWebConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres-store")
public class PostgresCentralConfigIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private ExecutorService asyncExecutor;
    private PostgresCentralConfigDao dao;

    @BeforeEach
    public void before() throws Exception {
        dataSource = PostgresDataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5);
        asyncExecutor = PostgresAsync.newExecutor(5);
        PostgresSchemaUpgrade.upgrade(dataSource);
        dao = new PostgresCentralConfigDao(dataSource, asyncExecutor);
        dao.addKeyType("web", ImmutableCentralWebConfig.class);
    }

    @AfterEach
    public void after() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }

    @Test
    public void writeAndReadCentralConfig() throws Exception {
        assertThat(dao.read("web")).isNull();

        ImmutableCentralWebConfig written = ImmutableCentralWebConfig.builder()
                .sessionTimeoutMinutes(31)
                .sessionCookieName("GLOWROOT_SESSION_ID2")
                .build();
        dao.writeWithoutOptimisticLocking("web", written).toCompletableFuture().join();

        Object read = dao.read("web").join();
        assertThat(read).isInstanceOf(ImmutableCentralWebConfig.class);
        ImmutableCentralWebConfig web = (ImmutableCentralWebConfig) read;
        assertThat(web.sessionTimeoutMinutes()).isEqualTo(31);
        assertThat(web.sessionCookieName()).isEqualTo("GLOWROOT_SESSION_ID2");
    }
}
