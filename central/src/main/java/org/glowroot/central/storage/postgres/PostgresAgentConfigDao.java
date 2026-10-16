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
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.sql.DataSource;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.AgentConfigDao.AgentConfigAndUpdateToken;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.ImmutableAgentConfigAndUpdateToken;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Postgres port of Cassandra {@code AgentConfigDao} (no ClusterManager cache yet).
 */
public class PostgresAgentConfigDao {

    private final DataSource dataSource;
    private final PostgresAgentDisplayDao agentDisplayDao;

    public PostgresAgentConfigDao(DataSource dataSource, PostgresAgentDisplayDao agentDisplayDao) {
        this.dataSource = dataSource;
        this.agentDisplayDao = agentDisplayDao;
    }

    public CompletionStage<AgentConfig> store(String agentId, AgentConfig agentConfig,
            boolean overwriteExisting) {
        return readAsync(agentId).thenCompose(existingAgentConfig -> {
            AgentConfig updatedAgentConfig =
                    buildUpdatedAgentConfig(agentConfig, existingAgentConfig, overwriteExisting);
            CompletionStage<?> chain = CompletableFuture.completedFuture(null);
            if (!updatedAgentConfig.equals(existingAgentConfig)) {
                chain = writeConfig(agentId, updatedAgentConfig, false, null);
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
                        AdvancedConfig advancedConfig = updatedAgentConfig.getAdvancedConfig();
                        AgentConfig rollupConfig = AgentConfig.newBuilder()
                                .setUiDefaultsConfig(updatedAgentConfig.getUiDefaultsConfig())
                                .setAdvancedConfig(AdvancedConfig.newBuilder()
                                        .setMaxQueryAggregates(advancedConfig.getMaxQueryAggregates())
                                        .setMaxServiceCallAggregates(
                                                advancedConfig.getMaxServiceCallAggregates()))
                                .build();
                        return writeConfig(loopAgentRollupId, rollupConfig, false, null);
                    }).thenCompose(v -> apply(indexAgentRollupId + 1));
                }
            };
            return chain.thenCompose(v -> lambda.apply(0)).thenApply(v -> updatedAgentConfig);
        });
    }

    public CompletionStage<?> update(String agentRollupId, AgentConfigUpdater agentConfigUpdater,
            boolean centralOnly) {
        return readRow(agentRollupId).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No config found: " + agentRollupId));
            }
            AgentConfigAndUpdateToken current = opt.get();
            AgentConfig currAgentConfig = current.config();
            if (!centralOnly && currAgentConfig.getConfigReadOnly()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "This agent is running with config.readOnly=true so"
                                + " it does not allow config updates via the central collector"));
            }
            ByteString currValue = currAgentConfig.toByteString();
            return agentConfigUpdater.updateAgentConfig(currAgentConfig)
                    .thenCompose(updatedAgentConfig -> {
                        UUID updateToken = centralOnly ? null : UUID.randomUUID();
                        boolean configUpdate = !centralOnly;
                        return compareAndSet(agentRollupId, updatedAgentConfig, configUpdate,
                                updateToken, currValue.toByteArray())
                                        .thenCompose(applied -> {
                                            if (!applied) {
                                                return CompletableFuture
                                                        .failedFuture(new OptimisticLockException());
                                            }
                                            String updatedDisplay = updatedAgentConfig
                                                    .getGeneralConfig().getDisplay();
                                            String currDisplay =
                                                    currAgentConfig.getGeneralConfig().getDisplay();
                                            if (!updatedDisplay.equals(currDisplay)) {
                                                return agentDisplayDao.store(agentRollupId,
                                                        updatedDisplay);
                                            }
                                            return CompletableFuture.completedFuture(null);
                                        });
                    });
        });
    }

    public CompletableFuture<@Nullable AgentConfig> readAsync(String agentRollupId) {
        return readRow(agentRollupId)
                .thenApply(opt -> opt.map(AgentConfigAndUpdateToken::config).orElse(null))
                .toCompletableFuture();
    }

    public CompletableFuture<Optional<AgentConfigAndUpdateToken>> readForUpdate(String agentId) {
        return readRow(agentId).toCompletableFuture();
    }

    public void markUpdated(String agentId, UUID configUpdateToken) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update agent_config set config_update = false, config_update_token = null "
                                + "where agent_rollup_id = ? and config_update_token = ?")) {
            ps.setString(1, agentId);
            ps.setObject(2, configUpdateToken);
            ps.executeUpdate();
        }
    }

    private CompletionStage<?> writeConfig(String agentRollupId, AgentConfig config,
            boolean configUpdate, /*@Nullable*/ UUID updateToken) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "insert into agent_config (agent_rollup_id, config, config_update, "
                                    + "config_update_token) values (?, ?, ?, ?) "
                                    + "on conflict (agent_rollup_id) do update set "
                                    + "config = excluded.config, "
                                    + "config_update = excluded.config_update, "
                                    + "config_update_token = excluded.config_update_token")) {
                ps.setString(1, agentRollupId);
                ps.setBytes(2, config.toByteArray());
                ps.setBoolean(3, configUpdate);
                if (updateToken == null) {
                    ps.setNull(4, Types.OTHER);
                } else {
                    ps.setObject(4, updateToken);
                }
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private CompletionStage<Boolean> compareAndSet(String agentRollupId, AgentConfig updated,
            boolean configUpdate, /*@Nullable*/ UUID updateToken, byte[] expectedConfig) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                String sql;
                if (updateToken == null) {
                    sql = "update agent_config set config = ? where agent_rollup_id = ? "
                            + "and config = ?";
                } else {
                    sql = "update agent_config set config = ?, config_update = ?, "
                            + "config_update_token = ? where agent_rollup_id = ? and config = ?";
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int i = 1;
                    ps.setBytes(i++, updated.toByteArray());
                    if (updateToken != null) {
                        ps.setBoolean(i++, configUpdate);
                        ps.setObject(i++, updateToken);
                    }
                    ps.setString(i++, agentRollupId);
                    ps.setBytes(i, expectedConfig);
                    future.complete(ps.executeUpdate() == 1);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private CompletionStage<Optional<AgentConfigAndUpdateToken>> readRow(String agentRollupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select config, config_update_token from agent_config "
                                    + "where agent_rollup_id = ?")) {
                ps.setString(1, agentRollupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<AgentConfigAndUpdateToken>empty();
                    }
                    byte[] bytes = checkNotNull(rs.getBytes("config"));
                    AgentConfig agentConfig = AgentConfig.parseFrom(bytes);
                    UUID updateToken = (UUID) rs.getObject("config_update_token");
                    return Optional.<AgentConfigAndUpdateToken>of(
                            ImmutableAgentConfigAndUpdateToken.builder()
                                    .config(agentConfig)
                                    .updateToken(updateToken)
                                    .build());
                }
            } catch (SQLException | InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static AgentConfig buildUpdatedAgentConfig(AgentConfig agentConfig,
            @Nullable AgentConfig existingAgentConfig, boolean overwriteExisting) {
        if (existingAgentConfig == null) {
            return agentConfig.toBuilder()
                    .clearGeneralConfig()
                    .build();
        }
        if (overwriteExisting) {
            return agentConfig.toBuilder()
                    .setGeneralConfig(existingAgentConfig.getGeneralConfig())
                    .build();
        }
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
                    properties.add(agentProperty);
                    continue;
                }
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

    public interface AgentConfigUpdater {
        CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig);
    }
}
