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
import org.glowroot.central.storage.postgres.PostgresActiveAgentDao;
import org.glowroot.central.storage.postgres.PostgresAggregateDao;
import org.glowroot.central.storage.postgres.PostgresAgentConfigDao;
import org.glowroot.central.storage.postgres.PostgresAgentDisplayDao;
import org.glowroot.central.storage.postgres.PostgresAlertingDisabledDao;
import org.glowroot.central.storage.postgres.PostgresAsync;
import org.glowroot.central.storage.postgres.PostgresCentralConfigDao;
import org.glowroot.central.storage.postgres.PostgresDataSourceFactory;
import org.glowroot.central.storage.postgres.PostgresEnvironmentDao;
import org.glowroot.central.storage.postgres.PostgresFullQueryTextDao;
import org.glowroot.central.storage.postgres.PostgresGaugeValueDao;
import org.glowroot.central.storage.postgres.PostgresHeartbeatDao;
import org.glowroot.central.storage.postgres.PostgresIncidentDao;
import org.glowroot.central.storage.postgres.PostgresRoleDao;
import org.glowroot.central.storage.postgres.PostgresPurge;
import org.glowroot.central.storage.postgres.PostgresSchemaUpgrade;
import org.glowroot.central.storage.postgres.PostgresSyntheticResultDao;
import org.glowroot.central.storage.postgres.PostgresTraceAttributeNameDao;
import org.glowroot.central.storage.postgres.PostgresTraceDao;
import org.glowroot.central.storage.postgres.PostgresTransactionTypeDao;
import org.glowroot.central.storage.postgres.PostgresUserDao;
import org.glowroot.central.storage.postgres.PostgresV09AgentRollupDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.util.RollupLevelService;

/**
 * Wires admin + metadata + experimental aggregate/trace/gauge/synthetic DAOs against PostgreSQL.
 */
public final class PostgresCentralAdminModule implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final ExecutorService asyncExecutor;
    private final ConfigRepositoryImpl configRepository;
    private final RollupLevelService rollupLevelService;
    private final PostgresAgentDisplayDao agentDisplayDao;
    private final PostgresAgentConfigDao agentConfigDao;
    private final PostgresHeartbeatDao heartbeatDao;
    private final PostgresEnvironmentDao environmentDao;
    private final PostgresAlertingDisabledDao alertingDisabledDao;
    private final PostgresIncidentDao incidentDao;
    private final PostgresActiveAgentDao activeAgentDao;
    private final PostgresTransactionTypeDao transactionTypeDao;
    private final PostgresTraceAttributeNameDao traceAttributeNameDao;
    private final PostgresV09AgentRollupDao v09AgentRollupDao;
    private final PostgresAggregateDao aggregateDao;
    private final PostgresTraceDao traceDao;
    private final PostgresGaugeValueDao gaugeValueDao;
    private final PostgresSyntheticResultDao syntheticResultDao;

    private PostgresCentralAdminModule(HikariDataSource dataSource, ExecutorService asyncExecutor,
            ConfigRepositoryImpl configRepository, RollupLevelService rollupLevelService,
            PostgresAgentDisplayDao agentDisplayDao, PostgresAgentConfigDao agentConfigDao,
            PostgresHeartbeatDao heartbeatDao, PostgresEnvironmentDao environmentDao,
            PostgresAlertingDisabledDao alertingDisabledDao, PostgresIncidentDao incidentDao,
            PostgresActiveAgentDao activeAgentDao, PostgresTransactionTypeDao transactionTypeDao,
            PostgresTraceAttributeNameDao traceAttributeNameDao,
            PostgresV09AgentRollupDao v09AgentRollupDao, PostgresAggregateDao aggregateDao,
            PostgresTraceDao traceDao, PostgresGaugeValueDao gaugeValueDao,
            PostgresSyntheticResultDao syntheticResultDao) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
        this.configRepository = configRepository;
        this.rollupLevelService = rollupLevelService;
        this.agentDisplayDao = agentDisplayDao;
        this.agentConfigDao = agentConfigDao;
        this.heartbeatDao = heartbeatDao;
        this.environmentDao = environmentDao;
        this.alertingDisabledDao = alertingDisabledDao;
        this.incidentDao = incidentDao;
        this.activeAgentDao = activeAgentDao;
        this.transactionTypeDao = transactionTypeDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
        this.v09AgentRollupDao = v09AgentRollupDao;
        this.aggregateDao = aggregateDao;
        this.traceDao = traceDao;
        this.gaugeValueDao = gaugeValueDao;
        this.syntheticResultDao = syntheticResultDao;
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
            PostgresPurge.deleteExpired(dataSource);
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
            RollupLevelService rollupLevelService =
                    new RollupLevelService(configRepository, clock);
            PostgresIncidentDao incidentDao =
                    new PostgresIncidentDao(dataSource, clock, asyncExecutor);
            PostgresActiveAgentDao activeAgentDao = new PostgresActiveAgentDao(dataSource,
                    agentDisplayDao, agentConfigDao, configRepository, rollupLevelService, clock,
                    asyncExecutor);
            PostgresTransactionTypeDao transactionTypeDao = new PostgresTransactionTypeDao(
                    dataSource, configRepository, clock, asyncExecutor);
            PostgresTraceAttributeNameDao traceAttributeNameDao =
                    new PostgresTraceAttributeNameDao(dataSource, configRepository, clock,
                            asyncExecutor);
            PostgresV09AgentRollupDao v09AgentRollupDao =
                    new PostgresV09AgentRollupDao(dataSource, asyncExecutor);
            PostgresFullQueryTextDao fullQueryTextDao = new PostgresFullQueryTextDao(dataSource,
                    configRepository, clock, asyncExecutor);
            PostgresAggregateDao aggregateDao = new PostgresAggregateDao(dataSource, activeAgentDao,
                    transactionTypeDao, fullQueryTextDao, configRepository, clock, asyncExecutor);
            PostgresTraceDao traceDao =
                    new PostgresTraceDao(dataSource, configRepository, clock, asyncExecutor);
            PostgresGaugeValueDao gaugeValueDao =
                    new PostgresGaugeValueDao(dataSource, configRepository, clock, asyncExecutor);
            PostgresSyntheticResultDao syntheticResultDao = new PostgresSyntheticResultDao(
                    dataSource, configRepository, clock, asyncExecutor);
            return new PostgresCentralAdminModule(dataSource, asyncExecutor, configRepository,
                    rollupLevelService, agentDisplayDao, agentConfigDao, heartbeatDao,
                    environmentDao, alertingDisabledDao, incidentDao, activeAgentDao,
                    transactionTypeDao, traceAttributeNameDao, v09AgentRollupDao, aggregateDao,
                    traceDao, gaugeValueDao, syntheticResultDao);
        } catch (Exception e) {
            PostgresAsync.shutdownQuietly(asyncExecutor);
            PostgresDataSourceFactory.closeQuietly(dataSource);
            throw e;
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public ConfigRepositoryImpl getConfigRepository() {
        return configRepository;
    }

    public RollupLevelService getRollupLevelService() {
        return rollupLevelService;
    }

    public PostgresAgentDisplayDao getAgentDisplayDao() {
        return agentDisplayDao;
    }

    public PostgresAgentConfigDao getAgentConfigDao() {
        return agentConfigDao;
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

    public PostgresIncidentDao getIncidentDao() {
        return incidentDao;
    }

    public PostgresActiveAgentDao getActiveAgentDao() {
        return activeAgentDao;
    }

    public PostgresTransactionTypeDao getTransactionTypeDao() {
        return transactionTypeDao;
    }

    public PostgresTraceAttributeNameDao getTraceAttributeNameDao() {
        return traceAttributeNameDao;
    }

    public PostgresV09AgentRollupDao getV09AgentRollupDao() {
        return v09AgentRollupDao;
    }

    public PostgresAggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public PostgresTraceDao getTraceDao() {
        return traceDao;
    }

    public PostgresGaugeValueDao getGaugeValueDao() {
        return gaugeValueDao;
    }

    public PostgresSyntheticResultDao getSyntheticResultDao() {
        return syntheticResultDao;
    }

    @Override
    public void close() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }
}
