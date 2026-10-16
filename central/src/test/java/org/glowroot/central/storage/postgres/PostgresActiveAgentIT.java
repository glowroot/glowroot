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

import java.util.List;

import org.glowroot.central.repo.PostgresCentralAdminModule;
import org.glowroot.common2.repo.ActiveAgentRepository.TopLevelAgentRollup;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
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
public class PostgresActiveAgentIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;
    private PostgresActiveAgentDao activeAgentDao;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
        activeAgentDao = admin.getActiveAgentDao();
        admin.getAgentConfigDao().store("agent-a", AgentConfig.getDefaultInstance(), false)
                .toCompletableFuture().join();
        admin.getAgentConfigDao().store("Env::node1", AgentConfig.getDefaultInstance(), false)
                .toCompletableFuture().join();
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void insertAndReadTopLevel() {
        long now = System.currentTimeMillis();
        activeAgentDao.insert("agent-a", now).toCompletableFuture().join();
        List<TopLevelAgentRollup> rollups = activeAgentDao
                .readActiveTopLevelAgentRollups(now - 60_000, now + 60_000, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(rollups).extracting(TopLevelAgentRollup::id).contains("agent-a");
    }

    @Test
    public void insertRollupChild() {
        long now = System.currentTimeMillis();
        activeAgentDao.insert("Env::node1", now).toCompletableFuture().join();
        List<TopLevelAgentRollup> rollups = activeAgentDao
                .readActiveTopLevelAgentRollups(now - 60_000, now + 60_000, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(rollups).extracting(TopLevelAgentRollup::id).contains("Env::");
        assertThat(activeAgentDao
                .readActiveChildAgentRollups("Env::", now - 60_000, now + 60_000,
                        CassandraProfile.web)
                .toCompletableFuture().join()).isNotEmpty();
    }
}
