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

import com.zaxxer.hikari.HikariDataSource;
import org.glowroot.central.storage.postgres.PostgresAgentConfigDao;
import org.glowroot.central.storage.postgres.PostgresAgentDisplayDao;
import org.glowroot.central.storage.postgres.PostgresCentralConfigDao;
import org.glowroot.central.storage.postgres.PostgresDataSourceFactory;
import org.glowroot.central.storage.postgres.PostgresRoleDao;
import org.glowroot.central.storage.postgres.PostgresSchemaUpgrade;
import org.glowroot.central.storage.postgres.PostgresUserDao;
import org.glowroot.common2.repo.ConfigRepository;

/**
 * Wires admin-facing {@link ConfigRepository} against PostgreSQL. Does not start full Central
 * (aggregates/traces/etc. still Cassandra-only).
 */
public final class PostgresCentralAdminModule implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final ConfigRepositoryImpl configRepository;

    private PostgresCentralAdminModule(HikariDataSource dataSource,
            ConfigRepositoryImpl configRepository) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
    }

    public static PostgresCentralAdminModule create(String jdbcUrl, String username,
            String password, int maximumPoolSize, String symmetricEncryptionKey) throws Exception {
        HikariDataSource dataSource = PostgresDataSourceFactory.create(jdbcUrl, username, password,
                maximumPoolSize);
        try {
            PostgresSchemaUpgrade.upgrade(dataSource);
            PostgresCentralConfigDao centralConfigDao = new PostgresCentralConfigDao(dataSource);
            PostgresUserDao userDao = new PostgresUserDao(dataSource);
            PostgresRoleDao roleDao = new PostgresRoleDao(dataSource);
            PostgresAgentDisplayDao agentDisplayDao = new PostgresAgentDisplayDao(dataSource);
            PostgresAgentConfigDao agentConfigDao =
                    new PostgresAgentConfigDao(dataSource, agentDisplayDao);
            ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(
                    new PostgresCentralConfigStore(centralConfigDao),
                    new PostgresAgentConfigStore(agentConfigDao),
                    new PostgresUserStore(userDao),
                    new PostgresRoleStore(roleDao),
                    symmetricEncryptionKey);
            return new PostgresCentralAdminModule(dataSource, configRepository);
        } catch (Exception e) {
            PostgresDataSourceFactory.closeQuietly(dataSource);
            throw e;
        }
    }

    public ConfigRepository getConfigRepository() {
        return configRepository;
    }

    @Override
    public void close() {
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }
}
