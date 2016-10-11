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
package org.glowroot.central.storage;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Value;

import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
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
    private final PreparedStatement readAgentRollupPS;
    private final PreparedStatement readEnvironmentPS;
    private final PreparedStatement readAgentConfigPS;
    private final PreparedStatement readAgentConfigUpdatePS;
    private final PreparedStatement markAgentConfigUpdatedPS;

    private final PreparedStatement readSingleAgentOnePS;
    private final PreparedStatement insertAgentOnePS;
    private final PreparedStatement readAgentOnePS;

    private volatile @MonotonicNonNull ConfigRepository configRepository;

    private final LoadingCache<String, Optional<AgentConfig>> cache =
            CacheBuilder.newBuilder().build(new CacheLoader<String, Optional<AgentConfig>>() {
                @Override
                public Optional<AgentConfig> load(String agentId) throws Exception {
                    return Optional.fromNullable(readAgentConfigInternal(agentId));
                }
            });

    public AgentDao(Session session) {
        this.session = session;

        session.execute("create table if not exists agent (agent_id varchar, agent_rollup varchar,"
                + " environment blob, config blob, config_update boolean, config_update_token uuid,"
                + " primary key (agent_id)) " + WITH_LCS);
        // secondary index is needed for Cassandra 2.x (to avoid error on readAgentConfigUpdatePS)
        session.execute(
                "create index if not exists agent_config_update_idx on agent (config_update)");
        session.execute("create table if not exists agent_one (one int, agent_id varchar,"
                + " agent_rollup varchar, primary key (one, agent_id)) " + WITH_LCS);

        insertPS = session.prepare("insert into agent (agent_id, agent_rollup, environment, config,"
                + " config_update, config_update_token) values (?, ?, ?, ?, ?, ?)");
        insertConfigOnlyPS = session.prepare("insert into agent (agent_id, config, config_update,"
                + " config_update_token) values (?, ?, ?, ?)");
        readAgentRollupPS = session.prepare("select agent_rollup from agent where agent_id = ?");
        readEnvironmentPS = session.prepare("select environment from agent where agent_id = ?");
        readAgentConfigPS = session.prepare("select config from agent where agent_id = ?");
        readAgentConfigUpdatePS = session.prepare("select config, config_update_token from agent"
                + " where agent_id = ? and config_update = true allow filtering");
        markAgentConfigUpdatedPS = session.prepare("update agent set config_update = false,"
                + " config_update_token = null where agent_id = ? if config_update_token = ?");

        readSingleAgentOnePS = session
                .prepare("select agent_rollup from agent_one where one = 1 and agent_id = ?");
        insertAgentOnePS = session
                .prepare("insert into agent_one (one, agent_id, agent_rollup) values (1, ?, ?)");
        readAgentOnePS =
                session.prepare("select agent_id, agent_rollup from agent_one where one = 1");
    }

    public void setConfigRepository(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    public List<AgentRollup> readAgentRollups() {
        ResultSet results = session.execute(readAgentOnePS.bind());
        Set<String> topLevelAgentRollups = Sets.newHashSet();
        Set<String> bottomLevelAgentRollups = Sets.newHashSet();
        Multimap<String, String> parentChildMap = ArrayListMultimap.create();
        for (Row row : results) {
            String agentId = checkNotNull(row.getString(0));
            String agentRollup = row.getString(1);
            if (agentRollup == null) {
                topLevelAgentRollups.add(agentId);
            } else {
                bottomLevelAgentRollups.add(agentRollup);
                parentChildMap.put(agentRollup, agentId);
            }
        }
        for (String bottomLevelAgentRollup : bottomLevelAgentRollups) {
            List<String> agentRollups = getAgentRollups(bottomLevelAgentRollup);
            topLevelAgentRollups.add(agentRollups.get(0));
            for (int i = 1; i < agentRollups.size(); i++) {
                parentChildMap.put(agentRollups.get(i - 1), agentRollups.get(i));
            }
        }
        List<AgentRollup> rollups = Lists.newArrayList();
        for (String topLevelAgentRollup : Ordering.from(String.CASE_INSENSITIVE_ORDER)
                .sortedCopy(topLevelAgentRollups)) {
            rollups.add(createAgentRollup(topLevelAgentRollup, parentChildMap));
        }
        return rollups;
    }

    @Override
    public boolean isLeaf(String agentRollup) {
        BoundStatement boundStatement = readSingleAgentOnePS.bind();
        boundStatement.setString(0, agentRollup);
        return !session.execute(boundStatement).isExhausted();
    }

    @Override
    public boolean isAgentRollupForAgentId(String agentRollup, String agentId) {
        if (agentRollup.equals(agentId)) {
            return true;
        }
        BoundStatement boundStatement = readSingleAgentOnePS.bind();
        boundStatement.setString(0, agentRollup);
        Row row = session.execute(boundStatement).one();
        if (row == null) {
            return false;
        }
        return getAgentRollups(checkNotNull(row.getString(0))).contains(agentRollup);
    }

    // returns stored agent config
    public AgentConfig store(String agentId, @Nullable String agentRollup, Environment environment,
            AgentConfig agentConfig) throws InvalidProtocolBufferException {
        AgentConfig existingAgentConfig = null;
        // checking if agent exists in agent_one table since if it doesn't, then user no longer
        // sees the agent in the UI and and it doesn't make sense for it to have an existing config
        BoundStatement boundStatement = readSingleAgentOnePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        if (results.one() != null) {
            existingAgentConfig = readAgentConfig(agentId);
        }
        AgentConfig updatedAgentConfig;
        if (existingAgentConfig == null) {
            updatedAgentConfig = agentConfig;
        } else {
            // sync list of plugin properties, central property values win
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
            updatedAgentConfig = existingAgentConfig.toBuilder()
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
        }
        boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, agentRollup);
        boundStatement.setBytes(i++, ByteBuffer.wrap(environment.toByteArray()));
        boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
        // this method is only called by collectInit(), and agent will not consider collectInit()
        // to be successful until it receives updated agent config
        boundStatement.setBool(i++, false);
        boundStatement.setToNull(i++);
        session.execute(boundStatement);
        // insert into agent last so readEnvironment() and readAgentConfig() below are more likely
        // to return non-null
        boundStatement = insertAgentOnePS.bind();
        i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, agentRollup);
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
            // agent must have been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from agent, but not initial environment data
            return null;
        }
        return Environment.parseFrom(ByteString.copyFrom(bytes));
    }

    public @Nullable AgentConfigUpdate readForAgentConfigUpdate(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAgentConfigUpdatePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // no pending config update for this agent (or agent has been manually deleted)
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        UUID configUpdateToken = checkNotNull(row.getUUID(1));
        return ImmutableAgentConfigUpdate.builder()
                .config(AgentConfig.parseFrom(ByteString.copyFrom(bytes)))
                .configUpdateToken(configUpdateToken)
                .build();
    }

    public void markAgentConfigUpdated(String agentId, UUID configUpdateToken) {
        BoundStatement boundStatement = markAgentConfigUpdatedPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setUUID(i++, configUpdateToken);
        session.execute(boundStatement);
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
        boundStatement.setBool(i++, true);
        boundStatement.setUUID(i++, UUIDs.random());
        session.execute(boundStatement);
        cache.invalidate(agentId);
    }

    // includes agentId itself
    // agentId is index 0
    // its direct parent is index 1
    // etc...
    List<String> readAgentRollups(String agentId) {
        BoundStatement boundStatement = readAgentRollupPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // agent must have been manually deleted
            return ImmutableList.of(agentId);
        }
        String agentRollup = row.getString(0);
        if (agentRollup == null) {
            return ImmutableList.of(agentId);
        }
        List<String> agentRollups = getAgentRollups(agentRollup);
        Collections.reverse(agentRollups);
        agentRollups.add(0, agentId);
        return agentRollups;
    }

    private @Nullable AgentConfig readAgentConfigInternal(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAgentConfigPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // agent must have been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from agent, but not initial agent config
            return null;
        }
        return AgentConfig.parseFrom(ByteString.copyFrom(bytes));
    }

    private AgentRollup createAgentRollup(String agentRollup,
            Multimap<String, String> parentChildMap) {
        Collection<String> childAgentRollups = parentChildMap.get(agentRollup);
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .name(agentRollup);
        for (String childAgentRollup : childAgentRollups) {
            builder.addChildren(createAgentRollup(childAgentRollup, parentChildMap));
        }
        return builder.build();
    }

    private static List<String> getAgentRollups(String agentRollup) {
        List<String> agentRollups = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollup.indexOf('/', lastFoundIndex)) != -1) {
            agentRollups.add(agentRollup.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex + 1;
        }
        agentRollups.add(agentRollup);
        return agentRollups;
    }

    @Value.Immutable
    public interface AgentConfigUpdate {
        AgentConfig config();
        UUID configUpdateToken();
    }
}
