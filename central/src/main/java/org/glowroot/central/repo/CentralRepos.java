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

import org.glowroot.common2.repo.AgentDisplayRepository;
import org.glowroot.common2.repo.AlertingDisabledRepository;
import org.glowroot.common2.repo.IncidentRepository;
import org.glowroot.common2.repo.TraceAttributeNameRepository;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.glowroot.common2.repo.util.RollupLevelService;

/**
 * Shared surface for Cassandra {@link CentralRepoModule} and Postgres wiring.
 */
public interface CentralRepos {

    AgentDisplayRepository getAgentDisplayDao();

    AgentConfigStore getAgentConfigDao();

    ConfigRepositoryImpl getConfigRepository();

    AlertingDisabledRepository getAlertingDisabledDao();

    RollupLevelService getRollupLevelService();

    ActiveAgentStore getActiveAgentDao();

    EnvironmentStore getEnvironmentDao();

    HeartbeatStore getHeartbeatDao();

    IncidentRepository getIncidentDao();

    TransactionTypeRepository getTransactionTypeDao();

    TraceAttributeNameRepository getTraceAttributeNameDao();

    AggregateDao getAggregateDao();

    TraceDao getTraceDao();

    GaugeValueDao getGaugeValueDao();

    SyntheticResultDao getSyntheticResultDao();

    V09AgentRollupStore getV09AgentRollupDao();

    void close() throws Exception;
}
