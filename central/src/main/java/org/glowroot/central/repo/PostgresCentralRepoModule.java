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

import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.AgentDisplayRepository;
import org.glowroot.common2.repo.AlertingDisabledRepository;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.common2.repo.TraceAttributeNameRepository;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.glowroot.common2.repo.util.RollupLevelService;

/**
 * Full Central repo surface against PostgreSQL (experimental).
 */
public final class PostgresCentralRepoModule implements CentralRepos {

    private final PostgresCentralAdminModule admin;
    private final AgentConfigStore agentConfigStore;
    private final PostgresRepoAdmin repoAdmin;

    private PostgresCentralRepoModule(PostgresCentralAdminModule admin,
            AgentConfigStore agentConfigStore, PostgresRepoAdmin repoAdmin) {
        this.admin = admin;
        this.agentConfigStore = agentConfigStore;
        this.repoAdmin = repoAdmin;
    }

    public static PostgresCentralRepoModule create(String jdbcUrl, String username, String password,
            int maximumPoolSize, String symmetricEncryptionKey, Clock clock) throws Exception {
        PostgresCentralAdminModule admin = PostgresCentralAdminModule.create(jdbcUrl, username,
                password, maximumPoolSize, symmetricEncryptionKey, clock);
        AgentConfigStore agentConfigStore = new PostgresAgentConfigStore(admin.getAgentConfigDao());
        PostgresRepoAdmin repoAdmin =
                new PostgresRepoAdmin(admin.getDataSource(), admin.getActiveAgentDao(), clock);
        return new PostgresCentralRepoModule(admin, agentConfigStore, repoAdmin);
    }

    public PostgresRepoAdmin getRepoAdmin() {
        return repoAdmin;
    }

    @Override
    public AgentDisplayRepository getAgentDisplayDao() {
        return admin.getAgentDisplayDao();
    }

    @Override
    public AgentConfigStore getAgentConfigDao() {
        return agentConfigStore;
    }

    @Override
    public ConfigRepositoryImpl getConfigRepository() {
        return admin.getConfigRepository();
    }

    @Override
    public AlertingDisabledRepository getAlertingDisabledDao() {
        return admin.getAlertingDisabledDao();
    }

    @Override
    public RollupLevelService getRollupLevelService() {
        return admin.getRollupLevelService();
    }

    @Override
    public ActiveAgentStore getActiveAgentDao() {
        return admin.getActiveAgentDao();
    }

    @Override
    public EnvironmentStore getEnvironmentDao() {
        return admin.getEnvironmentDao();
    }

    @Override
    public HeartbeatStore getHeartbeatDao() {
        return admin.getHeartbeatDao();
    }

    @Override
    public IncidentRepository getIncidentDao() {
        return admin.getIncidentDao();
    }

    @Override
    public TransactionTypeRepository getTransactionTypeDao() {
        return admin.getTransactionTypeDao();
    }

    @Override
    public TraceAttributeNameRepository getTraceAttributeNameDao() {
        return admin.getTraceAttributeNameDao();
    }

    @Override
    public AggregateDao getAggregateDao() {
        return admin.getAggregateDao();
    }

    @Override
    public TraceDao getTraceDao() {
        return admin.getTraceDao();
    }

    @Override
    public GaugeValueDao getGaugeValueDao() {
        return admin.getGaugeValueDao();
    }

    @Override
    public SyntheticResultDao getSyntheticResultDao() {
        return admin.getSyntheticResultDao();
    }

    @Override
    public V09AgentRollupStore getV09AgentRollupDao() {
        return admin.getV09AgentRollupDao();
    }

    @Override
    public void close() {
        admin.close();
    }
}
