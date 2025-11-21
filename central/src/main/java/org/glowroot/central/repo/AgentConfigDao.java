/*
 * Copyright 2017-2023 the original author or authors.
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

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.util.AsyncCache;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.immutables.value.Value;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

// TODO agent config records never expire for abandoned agent rollup ids
public class AgentConfigDao {

    private final Session session;
    private final AgentDisplayDao agentDisplayDao;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement updatePS;
    private final PreparedStatement updateCentralOnlyPS;
    private final PreparedStatement markUpdatedPS;

    private final AsyncCache<String, Optional<AgentConfigAndUpdateToken>> agentConfigCache;

    private final Executor asyncExecutor;

    AgentConfigDao(Session session, AgentDisplayDao agentDisplayDao, ClusterManager clusterManager,
                   int targetMaxActiveAgentsInPast7Days, Executor asyncExecutor) throws Exception {
        this.session = session;
        this.agentDisplayDao = agentDisplayDao;
        this.asyncExecutor = asyncExecutor;

        session.createTableWithLCS("create table if not exists agent_config (agent_rollup_id"
                + " varchar, config blob, config_update boolean, config_update_token uuid, primary"
                + " key (agent_rollup_id))");
        // secondary index is needed for Cassandra 2.x (to avoid error on readUpdatePS)
        session.updateSchemaWithRetry(
                "create index if not exists config_update_idx on agent_config (config_update)");

        insertPS = session.prepare("insert into agent_config (agent_rollup_id, config,"
                + " config_update, config_update_token) values (?, ?, ?, ?)");
        updatePS = session.prepare("update agent_config set config = ?, config_update = ?,"
                + " config_update_token = ? where agent_rollup_id = ? if config = ?");
        updateCentralOnlyPS = session.prepare(
                "update agent_config set config = ? where agent_rollup_id = ? if config = ?");
        readPS = session.prepare(
                "select config, config_update_token from agent_config where agent_rollup_id = ?");

        markUpdatedPS = session.prepare("update agent_config set config_update = false,"
                + " config_update_token = null where agent_rollup_id = ? if config_update_token"
                + " = ?");

        agentConfigCache = clusterManager.createPerAgentAsyncCache("agentConfigCache",
                targetMaxActiveAgentsInPast7Days, new AgentConfigCacheLoader());
    }

    public CompletionStage<AgentConfig> store(String agentId, AgentConfig agentConfig, boolean overwriteExisting) {
        return readAsync(agentId).thenCompose(existingAgentConfig -> {

            AgentConfig updatedAgentConfig =
                    buildUpdatedAgentConfig(agentConfig, existingAgentConfig, overwriteExisting);
            CompletionStage<?> chain = CompletableFuture.completedFuture(null);
            if (!updatedAgentConfig.equals(existingAgentConfig)) {
                int i = 0;
                BoundStatement boundStatement = insertPS.bind()
                        .setString(i++, agentId)
                        .setByteBuffer(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()))
                        // setting config_update to false as this method is only called by collectInit(), and
                        // agent will not consider collectInit() to be successful until it receives updated
                        // agent config
                        .setBoolean(i++, false)
                        .setToNull(i++);
                chain = session.writeAsync(boundStatement, CassandraProfile.collector).thenRun(() -> agentConfigCache.invalidate(agentId));
            }
            String agentRollupId = AgentRollupIds.getParent(agentId);
            if (agentRollupId == null) {
                return chain.thenApply(v -> updatedAgentConfig);
            }
            List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
            Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                @Override
                public CompletionStage<?> apply(Integer indexAgentRollupId) {
                    if (indexAgentRollupId >= agentRollupIds.size()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    String loopAgentRollupId = agentRollupIds.get(indexAgentRollupId);
                    return readAsync(loopAgentRollupId).thenCompose(rollupAgent -> {
                        if (rollupAgent != null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        // there is no config for rollup yet
                        // so insert initial config propagating ui config and advanced config properties
                        // that pertain to rollups
                        int i = 0;
                        AdvancedConfig advancedConfig = updatedAgentConfig.getAdvancedConfig();
                        BoundStatement boundStatement = insertPS.bind()
                                .setString(i++, loopAgentRollupId)
                                .setByteBuffer(i++, ByteBuffer.wrap(AgentConfig.newBuilder()
                                        .setUiDefaultsConfig(updatedAgentConfig.getUiDefaultsConfig())
                                        .setAdvancedConfig(AdvancedConfig.newBuilder()
                                                .setMaxQueryAggregates(advancedConfig.getMaxQueryAggregates())
                                                .setMaxServiceCallAggregates(
                                                        advancedConfig.getMaxServiceCallAggregates()))
                                        .build()
                                        .toByteArray()))
                                .setBoolean(i++, false)
                                .setToNull(i++);
                        return session.writeAsync(boundStatement, CassandraProfile.collector)
                                .thenRun(() -> agentConfigCache.invalidate(loopAgentRollupId));
                    }).thenCompose(v -> apply(indexAgentRollupId + 1));
                }
            };
            return chain.thenCompose(v -> lambda.apply(0)).thenApply(v -> updatedAgentConfig);
        });
    }

    CompletionStage<?> update(String agentRollupId, AgentConfigUpdater agentConfigUpdater, CassandraProfile profile) {
        return update(agentRollupId, agentConfigUpdater, false, profile);
    }

    // only call this method when updating AgentConfig data that resides only on central
    CompletionStage<?> updateCentralOnly(String agentRollupId, AgentConfigUpdater agentConfigUpdater, CassandraProfile profile) {
        return update(agentRollupId, agentConfigUpdater, true, profile);
    }

    CompletionStage<?> update(String agentRollupId, AgentConfigUpdater agentConfigUpdater, boolean centralOnly, CassandraProfile profile) {
//        for (int j = 0; j < 10; j++) {
//            MILLISECONDS.sleep(200);
//        }
//        throw new OptimisticLockException();


        BoundStatement bndStmt = readPS.bind()
                .setString(0, agentRollupId);

        return session.readAsync(bndStmt, profile).thenCompose(results -> {
            Row row1 = results.one();
            if (row1 == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No config found: " + agentRollupId));
            }
            ByteString currValue = ByteString.copyFrom(checkNotNull(row1.getByteBuffer(0)));
            AgentConfig currAgentConfig;
            try {
                currAgentConfig = AgentConfig.parseFrom(currValue);
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
            if (!centralOnly && currAgentConfig.getConfigReadOnly()) {
                return CompletableFuture.failedFuture(new IllegalStateException("This agent is running with config.readOnly=true so"
                        + " it does not allow config updates via the central collector"));
            }
            return agentConfigUpdater.updateAgentConfig(currAgentConfig).thenCompose(updatedAgentConfig -> {
                BoundStatement boundStatement;
                if (centralOnly) {
                    boundStatement = updateCentralOnlyPS.bind();
                } else {
                    boundStatement = updatePS.bind();
                }
                int i = 0;
                boundStatement = boundStatement.setByteBuffer(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
                if (!centralOnly) {
                    boundStatement = boundStatement.setBoolean(i++, true)
                            .setUuid(i++, Uuids.random());
                }
                boundStatement = boundStatement.setString(i++, agentRollupId)
                        .setByteBuffer(i++, ByteBuffer.wrap(currValue.toByteArray()));
                boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
                return session.updateAsync(boundStatement, profile).thenCompose(asyncresults -> {
                    Row row = checkNotNull(asyncresults.one());
                    boolean applied = row.getBoolean("[applied]");
                    if (applied) {
                        agentConfigCache.invalidate(agentRollupId);
                        String updatedDisplay = updatedAgentConfig.getGeneralConfig().getDisplay();
                        String currDisplay = currAgentConfig.getGeneralConfig().getDisplay();
                        if (!updatedDisplay.equals(currDisplay)) {
                            return agentDisplayDao.store(agentRollupId, updatedDisplay);
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                });
            });
        });
    }

    public CompletableFuture<AgentConfig> readAsync(String agentRollupId) {
        return agentConfigCache.get(agentRollupId).thenApply(agent -> agent.map(AgentConfigAndUpdateToken::config).orElse(null));
    }

    // does not apply to agent rollups
    public CompletableFuture<Optional<AgentConfigAndUpdateToken>> readForUpdate(String agentId) {
        return agentConfigCache.get(agentId);
    }

    // does not apply to agent rollups
    public void markUpdated(String agentId, UUID configUpdateToken) throws Exception {
        int i = 0;
        BoundStatement boundStatement = markUpdatedPS.bind()
                .setString(i++, agentId)
                .setUuid(i++, configUpdateToken);
        // consistency level must be at least LOCAL_SERIAL
        if (boundStatement.getSerialConsistencyLevel() != ConsistencyLevel.SERIAL) {
            boundStatement = boundStatement.setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL);
        }
        session.update(boundStatement, CassandraProfile.collector);
    }

    private static AgentConfig buildUpdatedAgentConfig(AgentConfig agentConfig,
                                                       @Nullable AgentConfig existingAgentConfig, boolean overwriteExisting) {
        if (existingAgentConfig == null) {
            return agentConfig.toBuilder()
                    // agent should not send general config, but clearing it just to be safe
                    .clearGeneralConfig()
                    .build();
        }
        if (overwriteExisting) {
            return agentConfig.toBuilder()
                    // preserve existing general config
                    .setGeneralConfig(existingAgentConfig.getGeneralConfig())
                    .build();
        }
        // absorb new plugin properties/labels/etc from agent into existing agent config
        Map<String, PluginConfig> existingPluginConfigs = new HashMap<>();
        for (PluginConfig existingPluginConfig : existingAgentConfig.getPluginConfigList()) {
            existingPluginConfigs.put(existingPluginConfig.getId(), existingPluginConfig);
        }
        List<PluginConfig> pluginConfigs = new ArrayList<>();
        for (PluginConfig agentPluginConfig : agentConfig.getPluginConfigList()) {
            PluginConfig existingPluginConfig =
                    existingPluginConfigs.get(agentPluginConfig.getId());
            if (existingPluginConfig == null) {
                pluginConfigs.add(agentPluginConfig);
                continue;
            }
            Map<String, PluginProperty> existingProperties = new HashMap<>();
            for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
                existingProperties.put(existingProperty.getName(), existingProperty);
            }
            List<PluginProperty> properties = new ArrayList<>();
            for (PluginProperty agentProperty : agentPluginConfig.getPropertyList()) {
                PluginProperty existingProperty =
                        existingProperties.get(agentProperty.getName());
                if (existingProperty == null) {
                    properties.add(agentProperty);
                    continue;
                }
                if (existingProperty.getValue().getValCase() != agentProperty.getValue()
                        .getValCase()) {
                    // the agent property type changed (e.g. was upgraded from comma-separated
                    // string property to list property)
                    properties.add(agentProperty);
                    continue;
                }
                // overlay existing property value
                properties.add(agentProperty.toBuilder()
                        .setValue(existingProperty.getValue())
                        .build());
            }
            pluginConfigs.add(PluginConfig.newBuilder()
                    .setId(agentPluginConfig.getId())
                    .setName(agentPluginConfig.getName())
                    .addAllProperty(properties)
                    .build());
        }
        return existingAgentConfig.toBuilder()
                .clearPluginConfig()
                .addAllPluginConfig(pluginConfigs)
                .build();
    }

    private class AgentConfigCacheLoader
            implements AsyncCache.AsyncCacheLoader<String, Optional<AgentConfigAndUpdateToken>> {
        @Override
        public CompletableFuture<Optional<AgentConfigAndUpdateToken>> load(String agentRollupId) {
            BoundStatement boundStatement = readPS.bind()
                    .setString(0, agentRollupId);
            return session.readAsync(boundStatement, CassandraProfile.collector)
                    .thenApplyAsync(results -> Optional.ofNullable(results.one())
                            .map(row -> {
                                int i = 0;
                                ByteBuffer bytes = checkNotNull(row.getByteBuffer(i++));
                                AgentConfig agentConfig = null;
                                try {
                                    agentConfig = AgentConfig.parseFrom(bytes);
                                } catch (InvalidProtocolBufferException e) {
                                    throw new RuntimeException(e);
                                }
                                UUID updateToken = row.getUuid(i);
                                return (AgentConfigAndUpdateToken) ImmutableAgentConfigAndUpdateToken.builder()
                                        .config(agentConfig)
                                        .updateToken(updateToken)
                                        .build();
                            }), asyncExecutor)
                    .toCompletableFuture();
        }
    }

    interface AgentConfigUpdater {
        CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig);
    }

    @Value.Immutable
    public interface AgentConfigAndUpdateToken {
        AgentConfig config();

        @Nullable
        UUID updateToken();
    }
}
