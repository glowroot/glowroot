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
import java.util.Map;

import org.glowroot.central.repo.PostgresCentralAdminModule;
import org.glowroot.common2.repo.SyntheticResult;
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
public class PostgresSyntheticResultIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;
    private PostgresSyntheticResultDao syntheticDao;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
        syntheticDao = admin.getSyntheticResultDao();
        syntheticDao.truncateAll();
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void storeReadAndRollupFallback() throws Exception {
        syntheticDao.store("one", "mon-1", "Monitor One", 60_000, 1_000_000, null)
                .toCompletableFuture().join();
        syntheticDao.store("one", "mon-1", "Monitor One", 120_000, 2_000_000, "boom")
                .toCompletableFuture().join();

        Map<String, String> ids = syntheticDao.getSyntheticMonitorIds("one", 0, 200_000);
        assertThat(ids).containsEntry("mon-1", "Monitor One");

        List<SyntheticResult> l0 = syntheticDao
                .readSyntheticResults("one", "mon-1", 0, 200_000, 0)
                .toCompletableFuture().join();
        assertThat(l0).hasSize(2);

        List<SyntheticResult> l1 = syntheticDao
                .readSyntheticResults("one", "mon-1", 0, 200_000, 1)
                .toCompletableFuture().join();
        assertThat(l1).isNotEmpty();
    }
}
