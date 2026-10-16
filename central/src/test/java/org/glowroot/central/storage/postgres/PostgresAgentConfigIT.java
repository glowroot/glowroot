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
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
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
public class PostgresAgentConfigIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private ExecutorService asyncExecutor;
    private PostgresAgentConfigDao agentConfigDao;

    @BeforeEach
    public void before() throws Exception {
        dataSource = PostgresDataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5);
        asyncExecutor = PostgresAsync.newExecutor(5);
        PostgresSchemaUpgrade.upgrade(dataSource);
        agentConfigDao = new PostgresAgentConfigDao(dataSource,
                new PostgresAgentDisplayDao(dataSource, asyncExecutor), asyncExecutor);
    }

    @AfterEach
    public void after() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false).toCompletableFuture().get();

        AgentConfig readAgentConfig = agentConfigDao.readAsync("a").get();
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false).toCompletableFuture().get();
        agentConfigDao.store("a", AgentConfig.newBuilder()
                .setTransactionConfig(TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder()
                                .setValue(1234)))
                .build(), false).toCompletableFuture().get();

        AgentConfig readAgentConfig = agentConfigDao.readAsync("a").get();
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }
}
