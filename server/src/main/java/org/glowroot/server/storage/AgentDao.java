/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.server.storage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableAgentRollup;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO need to validate cannot have agentIds "A/B/C" and "A/B" since there is logic elsewhere
// (at least in the UI) that "A/B" is only a rollup
public class AgentDao implements AgentRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement insertConfigOnlyPS;
    private final PreparedStatement readEnvironmentPS;
    private final PreparedStatement readAgentConfigPS;

    private final PreparedStatement existsRollupPS;
    private final PreparedStatement insertRollupPS;
    private final PreparedStatement readRollupPS;

    private volatile @MonotonicNonNull ConfigRepository configRepository;

    private final LoadingCache<String, Optional<AgentConfig>> cache = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, Optional<AgentConfig>>() {
                @Override
                public Optional<AgentConfig> load(String agentId) throws Exception {
                    return Optional.fromNullable(readAgentConfigInternal(agentId));
                }
            });

    public AgentDao(Session session) {
        this.session = session;

        upgradeIfNeeded(session);

        session.execute("create table if not exists agent (agent_id varchar, environment blob,"
                + " config blob, primary key (agent_id)) " + WITH_LCS);
        session.execute("create table if not exists agent_rollup (one int, agent_rollup varchar,"
                + " leaf boolean, primary key (one, agent_rollup)) " + WITH_LCS);

        insertPS = session
                .prepare("insert into agent (agent_id, environment, config) values (?, ?, ?)");
        insertConfigOnlyPS = session.prepare("insert into agent (agent_id, config) values (?, ?)");
        readEnvironmentPS = session.prepare("select environment from agent where agent_id = ?");
        readAgentConfigPS = session.prepare("select config from agent where agent_id = ?");

        existsRollupPS = session.prepare(
                "select agent_rollup from agent_rollup where one = 1 and agent_rollup = ?");
        insertRollupPS = session
                .prepare("insert into agent_rollup (one, agent_rollup, leaf) values (1, ?, ?)");
        readRollupPS = session.prepare("select agent_rollup, leaf from agent_rollup where one = 1");
    }

    public void setConfigRepository(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    public List<AgentRollup> readAgentRollups() {
        ResultSet results = session.execute(readRollupPS.bind());
        List<AgentRollup> rollups = Lists.newArrayList();
        for (Row row : results) {
            String agentRollup = checkNotNull(row.getString(0));
            boolean leaf = row.getBool(1);
            rollups.add(ImmutableAgentRollup.of(agentRollup, leaf));
        }
        return rollups;
    }

    // returns stored agent config
    public AgentConfig store(String agentId, Environment environment, AgentConfig agentConfig)
            throws InvalidProtocolBufferException {
        AgentConfig existingAgentConfig = null;
        // checking if agent exists in agent_rollup table since if it doesn't, then user no longer
        // sees the agent in the UI and and it doesn't make sense for it to have an existing config
        BoundStatement boundStatement = existsRollupPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        if (results.one() != null) {
            existingAgentConfig = readAgentConfig(agentId);
        }
        AgentConfig updatedAgentConfig;
        if (existingAgentConfig == null) {
            updatedAgentConfig = agentConfig;
        } else {
            // sync list of plugin properties, glowroot server property values win
            Map<String, PluginConfig> existingPluginConfigs = Maps.newHashMap();
            for (PluginConfig existingPluginConfig : existingAgentConfig.getPluginConfigList()) {
                existingPluginConfigs.put(existingPluginConfig.getId(), existingPluginConfig);
            }
            List<PluginConfig> pluginConfigs = Lists.newArrayList();
            for (PluginConfig agentPluginConfig : agentConfig.getPluginConfigList()) {
                PluginConfig existingPluginConfig =
                        existingPluginConfigs.get(agentPluginConfig.getId());
                if (existingPluginConfig == null) {
                    pluginConfigs.add(agentPluginConfig);
                    continue;
                }
                Map<String, PluginProperty> existingProperties = Maps.newHashMap();
                for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
                    existingProperties.put(existingProperty.getName(), existingProperty);
                }
                List<PluginProperty> properties = Lists.newArrayList();
                for (PluginProperty agentProperty : agentPluginConfig.getPropertyList()) {
                    PluginProperty existingProperty =
                            existingProperties.get(agentProperty.getName());
                    if (existingProperty == null) {
                        properties.add(agentProperty);
                        continue;
                    }
                    // overlay existing property value
                    properties.add(PluginProperty.newBuilder(agentProperty)
                            .setValue(existingProperty.getValue())
                            .build());
                }
                pluginConfigs.add(PluginConfig.newBuilder()
                        .setId(agentPluginConfig.getId())
                        .setName(agentPluginConfig.getName())
                        .addAllProperty(properties)
                        .build());
            }
            updatedAgentConfig = AgentConfig.newBuilder(existingAgentConfig)
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
        }
        boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(environment.toByteArray()));
        boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
        session.execute(boundStatement);
        // insert into agent last so readEnvironment() and readAgentConfig() below are more likely
        // to return non-null
        boundStatement = insertRollupPS.bind();
        i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBool(i++, true);
        session.execute(boundStatement);
        cache.invalidate(agentId);
        return updatedAgentConfig;
    }

    @Override
    public @Nullable Environment readEnvironment(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readEnvironmentPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // must have just expired or been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from agent, but not initial system info data
            return null;
        }
        return Environment.parseFrom(ByteString.copyFrom(bytes));
    }

    @Nullable
    AgentConfig readAgentConfig(String agentId) {
        return cache.getUnchecked(agentId).orNull();
    }

    void storeAgentConfig(String agentId, AgentConfig agentConfig) {
        BoundStatement boundStatement = insertConfigOnlyPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
        session.execute(boundStatement);
        cache.invalidate(agentId);
    }

    private @Nullable AgentConfig readAgentConfigInternal(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAgentConfigPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // must have just expired or been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from agent, but not initial system info data
            return null;
        }
        return AgentConfig.parseFrom(ByteString.copyFrom(bytes));
    }

    // upgrade from 0.9.1 to 0.9.2
    private static void upgradeIfNeeded(Session session) {
        ResultSet results;
        try {
            results = session.execute("select agent_id, system_info from agent");
        } catch (InvalidQueryException e) {
            // system_info column does not exist
            return;
        }
        session.execute("alter table agent add environment blob");
        for (Row row : results) {
            PreparedStatement preparedStatement =
                    session.prepare("insert into agent (agent_id, environment) values (?, ?)");
            BoundStatement boundStatement = preparedStatement.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            session.execute(boundStatement);
        }
        session.execute("alter table agent drop system_info");
    }
}
