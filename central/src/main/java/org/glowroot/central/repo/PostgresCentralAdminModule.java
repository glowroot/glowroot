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
package org.glowroot.central.repo;

import java.util.concurrent.ExecutorService;

import com.zaxxer.hikari.HikariDataSource;
import org.glowroot.central.storage.postgres.PostgresAgentConfigDao;
import org.glowroot.central.storage.postgres.PostgresAgentDisplayDao;
import org.glowroot.central.storage.postgres.PostgresAlertingDisabledDao;
import org.glowroot.central.storage.postgres.PostgresAsync;
import org.glowroot.central.storage.postgres.PostgresCentralConfigDao;
import org.glowroot.central.storage.postgres.PostgresDataSourceFactory;
import org.glowroot.central.storage.postgres.PostgresEnvironmentDao;
import org.glowroot.central.storage.postgres.PostgresHeartbeatDao;
import org.glowroot.central.storage.postgres.PostgresRoleDao;
import org.glowroot.central.storage.postgres.PostgresSchemaUpgrade;
import org.glowroot.central.storage.postgres.PostgresUserDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;

/**
 * Wires admin-facing {@link ConfigRepository} against PostgreSQL. Does not start full Central
 * (aggregates/traces/etc. still Cassandra-only).
 */
public final class PostgresCentralAdminModule implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final ExecutorService asyncExecutor;
    private final ConfigRepositoryImpl configRepository;
    private final PostgresHeartbeatDao heartbeatDao;
    private final PostgresEnvironmentDao environmentDao;
    private final PostgresAlertingDisabledDao alertingDisabledDao;

    private PostgresCentralAdminModule(HikariDataSource dataSource, ExecutorService asyncExecutor,
            ConfigRepositoryImpl configRepository, PostgresHeartbeatDao heartbeatDao,
            PostgresEnvironmentDao environmentDao,
            PostgresAlertingDisabledDao alertingDisabledDao) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
        this.configRepository = configRepository;
        this.heartbeatDao = heartbeatDao;
        this.environmentDao = environmentDao;
        this.alertingDisabledDao = alertingDisabledDao;
    }

    public static PostgresCentralAdminModule create(String jdbcUrl, String username,
            String password, int maximumPoolSize, String symmetricEncryptionKey) throws Exception {
        return create(jdbcUrl, username, password, maximumPoolSize, symmetricEncryptionKey,
                Clock.systemClock());
    }

    public static PostgresCentralAdminModule create(String jdbcUrl, String username,
            String password, int maximumPoolSize, String symmetricEncryptionKey, Clock clock)
            throws Exception {
        HikariDataSource dataSource = PostgresDataSourceFactory.create(jdbcUrl, username, password,
                maximumPoolSize);
        ExecutorService asyncExecutor = PostgresAsync.newExecutor(maximumPoolSize);
        try {
            PostgresSchemaUpgrade.upgrade(dataSource);
            PostgresCentralConfigDao centralConfigDao =
                    new PostgresCentralConfigDao(dataSource, asyncExecutor);
            PostgresUserDao userDao = new PostgresUserDao(dataSource, asyncExecutor);
            PostgresRoleDao roleDao = new PostgresRoleDao(dataSource, asyncExecutor);
            PostgresAgentDisplayDao agentDisplayDao =
                    new PostgresAgentDisplayDao(dataSource, asyncExecutor);
            PostgresAgentConfigDao agentConfigDao =
                    new PostgresAgentConfigDao(dataSource, agentDisplayDao, asyncExecutor);
            PostgresHeartbeatDao heartbeatDao =
                    new PostgresHeartbeatDao(dataSource, clock, asyncExecutor);
            PostgresEnvironmentDao environmentDao =
                    new PostgresEnvironmentDao(dataSource, asyncExecutor);
            PostgresAlertingDisabledDao alertingDisabledDao =
                    new PostgresAlertingDisabledDao(dataSource, clock, asyncExecutor);
            ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(
                    new PostgresCentralConfigStore(centralConfigDao),
                    new PostgresAgentConfigStore(agentConfigDao),
                    new PostgresUserStore(userDao),
                    new PostgresRoleStore(roleDao),
                    symmetricEncryptionKey);
            return new PostgresCentralAdminModule(dataSource, asyncExecutor, configRepository,
                    heartbeatDao, environmentDao, alertingDisabledDao);
        } catch (Exception e) {
            PostgresAsync.shutdownQuietly(asyncExecutor);
            PostgresDataSourceFactory.closeQuietly(dataSource);
            throw e;
        }
    }

    public ConfigRepository getConfigRepository() {
        return configRepository;
    }

    public PostgresHeartbeatDao getHeartbeatDao() {
        return heartbeatDao;
    }

    public PostgresEnvironmentDao getEnvironmentDao() {
        return environmentDao;
    }

    public PostgresAlertingDisabledDao getAlertingDisabledDao() {
        return alertingDisabledDao;
    }

    @Override
    public void close() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }
}
