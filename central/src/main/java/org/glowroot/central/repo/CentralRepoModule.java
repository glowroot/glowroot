/*
 * Copyright 2017 the original author or authors.
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

import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.util.Clock;

public class CentralRepoModule {

    private final CentralConfigDao centralConfigDao;
    private final AgentDao agentDao;
    private final ConfigDao configDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final ConfigRepositoryImpl configRepository;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final AggregateDao aggregateDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final SyntheticResultDao syntheticResultDao;
    private final EnvironmentDao environmentDao;
    private final HeartbeatDao heartbeatDao;
    private final TriggeredAlertDao triggeredAlertDao;

    public CentralRepoModule(ClusterManager clusterManager, Session session,
            KeyspaceMetadata keyspaceMetadata, String cassandraSymmetricEncryptionKey, Clock clock)
            throws Exception {
        centralConfigDao = new CentralConfigDao(session, clusterManager);
        agentDao = new AgentDao(session, clusterManager);
        configDao = new ConfigDao(session, clusterManager);
        userDao = new UserDao(session, keyspaceMetadata, clusterManager);
        roleDao = new RoleDao(session, keyspaceMetadata, clusterManager);
        configRepository = new ConfigRepositoryImpl(agentDao, configDao, centralConfigDao, userDao,
                roleDao, cassandraSymmetricEncryptionKey);
        transactionTypeDao = new TransactionTypeDao(session, configRepository, clusterManager);
        fullQueryTextDao = new FullQueryTextDao(session, configRepository);
        aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao, fullQueryTextDao,
                configRepository, clock);
        traceAttributeNameDao =
                new TraceAttributeNameDao(session, configRepository, clusterManager);
        traceDao = new TraceDao(session, agentDao, transactionTypeDao, fullQueryTextDao,
                traceAttributeNameDao, configRepository, clock);
        gaugeValueDao =
                new GaugeValueDao(session, agentDao, configRepository, clusterManager, clock);
        syntheticResultDao = new SyntheticResultDao(session, configRepository, clock);
        environmentDao = new EnvironmentDao(session);
        heartbeatDao = new HeartbeatDao(session, agentDao, clock);
        triggeredAlertDao = new TriggeredAlertDao(session);
    }

    public CentralConfigDao getCentralConfigDao() {
        return centralConfigDao;
    }

    public AgentDao getAgentDao() {
        return agentDao;
    }

    public ConfigDao getConfigDao() {
        return configDao;
    }

    public UserDao getUserDao() {
        return userDao;
    }

    public RoleDao getRoleDao() {
        return roleDao;
    }

    public ConfigRepositoryImpl getConfigRepository() {
        return configRepository;
    }

    public TransactionTypeDao getTransactionTypeDao() {
        return transactionTypeDao;
    }

    public FullQueryTextDao getFullQueryTextDao() {
        return fullQueryTextDao;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceAttributeNameDao getTraceAttributeNameDao() {
        return traceAttributeNameDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
    }

    public GaugeValueDao getGaugeValueDao() {
        return gaugeValueDao;
    }

    public SyntheticResultDao getSyntheticResultDao() {
        return syntheticResultDao;
    }

    public EnvironmentDao getEnvironmentDao() {
        return environmentDao;
    }

    public HeartbeatDao getHeartbeatDao() {
        return heartbeatDao;
    }

    public TriggeredAlertDao getTriggeredAlertDao() {
        return triggeredAlertDao;
    }
}
