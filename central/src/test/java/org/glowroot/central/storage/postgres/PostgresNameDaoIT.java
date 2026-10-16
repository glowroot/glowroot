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
public class PostgresNameDaoIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void transactionTypeRoundTrip() {
        admin.getTransactionTypeDao().store(List.of("agent-a", "agent-a::"), "Web")
                .toCompletableFuture().join();
        List<String> types = admin.getTransactionTypeDao().read("agent-a").toCompletableFuture()
                .join();
        assertThat(types).contains("Web");
    }

    @Test
    public void traceAttributeNameRoundTrip() throws Exception {
        admin.getTraceAttributeNameDao().store("agent-a", "Web", "user").toCompletableFuture()
                .join();
        Map<String, List<String>> names = admin.getTraceAttributeNameDao().read("agent-a");
        assertThat(names.get("Web")).contains("user");
    }
}
