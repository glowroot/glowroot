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
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment.HostInfo;
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
public class PostgresMetadataIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private ExecutorService asyncExecutor;
    private Clock clock;
    private PostgresHeartbeatDao heartbeatDao;
    private PostgresEnvironmentDao environmentDao;
    private PostgresAlertingDisabledDao alertingDisabledDao;

    @BeforeEach
    public void before() throws Exception {
        dataSource = PostgresDataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5);
        asyncExecutor = PostgresAsync.newExecutor(5);
        clock = Clock.systemClock();
        PostgresSchemaUpgrade.upgrade(dataSource);
        heartbeatDao = new PostgresHeartbeatDao(dataSource, clock, asyncExecutor);
        environmentDao = new PostgresEnvironmentDao(dataSource, asyncExecutor);
        alertingDisabledDao = new PostgresAlertingDisabledDao(dataSource, clock, asyncExecutor);
    }

    @AfterEach
    public void after() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }

    @Test
    public void heartbeatStoreAndExists() throws Exception {
        long now = clock.currentTimeMillis();
        heartbeatDao.store("agent-a").toCompletableFuture().join();
        assertThat(heartbeatDao.exists("agent-a", now - 60_000, now + 60_000)
                .toCompletableFuture().join()).isTrue();
        assertThat(heartbeatDao.exists("agent-a", now + 60_000, now + 120_000)
                .toCompletableFuture().join()).isFalse();
    }

    @Test
    public void environmentRoundTrip() throws Exception {
        Environment env = Environment.newBuilder()
                .setHostInfo(HostInfo.newBuilder()
                        .setHostname("host1"))
                .build();
        environmentDao.store("agent-a", env).toCompletableFuture().join();
        Environment read = environmentDao.read("agent-a").toCompletableFuture().join();
        assertThat(read).isEqualTo(env);
    }

    @Test
    public void alertingDisabledRoundTrip() throws Exception {
        long until = clock.currentTimeMillis() + 60_000;
        alertingDisabledDao.setAlertingDisabledUntilTime("agent-a", until).toCompletableFuture()
                .join();
        assertThat(alertingDisabledDao.getAlertingDisabledUntilTime("agent-a")
                .toCompletableFuture().join()).isEqualTo(until);
        alertingDisabledDao.setAlertingDisabledUntilTime("agent-a", null).toCompletableFuture()
                .join();
        assertThat(alertingDisabledDao.getAlertingDisabledUntilTime("agent-a")
                .toCompletableFuture().join()).isNull();
    }
}
