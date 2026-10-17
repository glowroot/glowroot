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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;

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
public class PostgresPurgeIT {

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
    public void batchedPurgeRemovesExpiredRows() throws Exception {
        try (Connection connection = admin.getDataSource().getConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "insert into heartbeat (agent_id, central_capture_time, expires_at) "
                                + "values (?, ?, ?)")) {
            Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
            Timestamp future = new Timestamp(System.currentTimeMillis() + 3_600_000);
            for (int i = 0; i < 12; i++) {
                insert.setString(1, "agent-" + i);
                insert.setTimestamp(2, past);
                insert.setTimestamp(3, past);
                insert.addBatch();
            }
            insert.setString(1, "keep-" + UUID.randomUUID());
            insert.setTimestamp(2, past);
            insert.setTimestamp(3, future);
            insert.addBatch();
            insert.executeBatch();
        }

        int deleted = PostgresPurge.deleteExpiredBatched(admin.getDataSource(), 5);
        assertThat(deleted).isGreaterThanOrEqualTo(12);

        try (Connection connection = admin.getDataSource().getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from heartbeat where expires_at < now()");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
        try (Connection connection = admin.getDataSource().getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from heartbeat where agent_id like 'keep-%'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }
}
