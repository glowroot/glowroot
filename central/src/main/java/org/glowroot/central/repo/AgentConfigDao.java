/*
 * Copyright 2017-2018 the original author or authors.
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.protobuf.ByteString;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.repo.ActiveAgentDao.AgentConfigUpdate;
import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

// TODO agent config records never expire for abandoned agent rollup ids
public class AgentConfigDao {

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readForUpdatePS;
    private final PreparedStatement updatePS;
    private final PreparedStatement markUpdatedPS;

    private final Cache<String, Optional<AgentConfig>> agentConfigCache;

    AgentConfigDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

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
        readPS = session.prepare("select config from agent_config where agent_rollup_id = ?");

        readForUpdatePS = session.prepare("select config, config_update_token from agent_config"
                + " where agent_rollup_id = ? and config_update = true allow filtering");
        markUpdatedPS = session.prepare("update agent_config set config_update = false,"
                + " config_update_token = null where agent_rollup_id = ? if config_update_token"
                + " = ?");

        agentConfigCache =
                clusterManager.createCache("agentConfigCache", new AgentConfigCacheLoader());
    }

    public AgentConfig store(String agentId, AgentConfig agentConfig, boolean overwriteExisting)
            throws Exception {
        AgentConfig existingAgentConfig = read(agentId);
        AgentConfig updatedAgentConfig =
                buildUpdatedAgentConfig(agentConfig, existingAgentConfig, overwriteExisting);
        if (existingAgentConfig == null || !updatedAgentConfig.equals(existingAgentConfig)) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            // setting config_update to false as this method is only called by collectInit(), and
            // agent will not consider collectInit() to be successful until it receives updated
            // agent config
            boundStatement.setBool(i++, false);
            boundStatement.setToNull(i++);
            session.write(boundStatement);
            agentConfigCache.invalidate(agentId);
        }
        String agentRollupId = AgentRollupIds.getParent(agentId);
        if (agentRollupId != null) {
            List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
            for (String loopAgentRollupId : agentRollupIds) {
                if (read(loopAgentRollupId) != null) {
                    continue;
                }
                // there is no config for rollup yet
                // so insert initial config propagating ui config and advanced config properties
                // that pertain to rollups
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, loopAgentRollupId);
                AdvancedConfig advancedConfig = updatedAgentConfig.getAdvancedConfig();
                boundStatement.setBytes(i++, ByteBuffer.wrap(AgentConfig.newBuilder()
                        .setUiDefaultsConfig(updatedAgentConfig.getUiDefaultsConfig())
                        .setAdvancedConfig(AdvancedConfig.newBuilder()
                                .setMaxQueryAggregates(advancedConfig.getMaxQueryAggregates())
                                .setMaxServiceCallAggregates(
                                        advancedConfig.getMaxServiceCallAggregates()))
                        .build()
                        .toByteArray()));
                boundStatement.setBool(i++, false);
                boundStatement.setToNull(i++);
                session.write(boundStatement);
                agentConfigCache.invalidate(loopAgentRollupId);
            }
        }
        return updatedAgentConfig;
    }

    void update(String agentRollupId, AgentConfigUpdater agentConfigUpdater) throws Exception {
        for (int j = 0; j < 10; j++) {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.read(boundStatement);
            Row row = results.one();
            if (row == null) {
                throw new IllegalStateException("No config found: " + agentRollupId);
            }
            ByteString currValue = ByteString.copyFrom(checkNotNull(row.getBytes(0)));
            AgentConfig currAgentConfig = AgentConfig.parseFrom(currValue);
            if (currAgentConfig.getConfigReadOnly()) {
                throw new IllegalStateException("This agent is running with config.readOnly=true so"
                        + " it does not allow config updates via the central collector");
            }
            AgentConfig updatedAgentConfig = agentConfigUpdater.updateAgentConfig(currAgentConfig);

            boundStatement = updatePS.bind();
            int i = 0;
            boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            boundStatement.setBool(i++, true);
            boundStatement.setUUID(i++, UUIDs.random());
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(currValue.toByteArray()));
            results = session.update(boundStatement);
            row = checkNotNull(results.one());
            boolean applied = row.getBool("[applied]");
            if (applied) {
                agentConfigCache.invalidate(agentRollupId);
                return;
            }
            MILLISECONDS.sleep(200);
        }
        throw new OptimisticLockException();
    }

    public String readAgentRollupDisplay(String agentRollupId) throws Exception {
        List<String> displayParts = readAgentRollupDisplayParts(agentRollupId);
        return Joiner.on(" :: ").join(displayParts);
    }

    public List<String> readAgentRollupDisplayParts(String agentRollupId) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<String> displayParts = new ArrayList<>();
        for (ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size()); i
                .hasPrevious();) {
            displayParts.add(readAgentRollupLastDisplayPart(i.previous()));
        }
        return displayParts;
    }

    public @Nullable AgentConfig read(String agentRollupId) throws Exception {
        return agentConfigCache.get(agentRollupId).orNull();
    }

    // does not apply to agent rollups
    public @Nullable AgentConfigUpdate readForUpdate(String agentId) throws Exception {
        BoundStatement boundStatement = readForUpdatePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.read(boundStatement);
        Row row = results.one();
        if (row == null) {
            // no pending config update for this agent (or agent has been manually deleted)
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        UUID configUpdateToken = checkNotNull(row.getUUID(1));
        return ImmutableAgentConfigUpdate.builder()
                .config(AgentConfig.parseFrom(bytes))
                .configUpdateToken(configUpdateToken)
                .build();
    }

    // does not apply to agent rollups
    public void markUpdated(String agentId, UUID configUpdateToken) throws Exception {
        BoundStatement boundStatement = markUpdatedPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setUUID(i++, configUpdateToken);
        session.update(boundStatement);
    }

    private String readAgentRollupLastDisplayPart(String agentRollupId) throws Exception {
        AgentConfig agentConfig = read(agentRollupId);
        if (agentConfig == null) {
            return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
        }
        String display = agentConfig.getGeneralConfig().getDisplay();
        if (display.isEmpty()) {
            return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
        }
        return display;
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

    private class AgentConfigCacheLoader implements CacheLoader<String, Optional<AgentConfig>> {
        @Override
        public Optional<AgentConfig> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.read(boundStatement);
            Row row = results.one();
            if (row == null) {
                // agent must have been manually deleted
                return Optional.absent();
            }
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            return Optional.of(AgentConfig.parseFrom(bytes));
        }
    }

    interface AgentConfigUpdater {
        AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception;
    }
}
