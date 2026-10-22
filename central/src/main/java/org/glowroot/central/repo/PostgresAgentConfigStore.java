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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.glowroot.central.repo.AgentConfigDao.AgentConfigAndUpdateToken;
import org.glowroot.central.repo.AgentConfigDao.AgentConfigUpdater;
import org.glowroot.central.storage.postgres.PostgresAgentConfigDao;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

final class PostgresAgentConfigStore implements AgentConfigStore {

    private final PostgresAgentConfigDao dao;

    PostgresAgentConfigStore(PostgresAgentConfigDao dao) {
        this.dao = dao;
    }

    @Override
    public CompletionStage<AgentConfig> store(String agentId, AgentConfig agentConfig,
            boolean overwriteExisting) {
        return dao.store(agentId, agentConfig, overwriteExisting);
    }

    @Override
    public CompletionStage<?> update(String agentRollupId, AgentConfigUpdater agentConfigUpdater,
            CassandraProfile profile) {
        return dao.update(agentRollupId, agentConfigUpdater::updateAgentConfig, false);
    }

    @Override
    public CompletionStage<?> updateCentralOnly(String agentRollupId,
            AgentConfigUpdater agentConfigUpdater, CassandraProfile profile) {
        return dao.update(agentRollupId, agentConfigUpdater::updateAgentConfig, true);
    }

    @Override
    @SuppressWarnings("nullness")
    public CompletableFuture<AgentConfig> readAsync(String agentRollupId) {
        return dao.readAsync(agentRollupId);
    }

    @Override
    public CompletableFuture<Optional<AgentConfigAndUpdateToken>> readForUpdate(String agentId) {
        return dao.readForUpdate(agentId);
    }

    @Override
    public void markUpdated(String agentId, UUID configUpdateToken) throws Exception {
        dao.markUpdated(agentId, configUpdateToken);
    }
}
