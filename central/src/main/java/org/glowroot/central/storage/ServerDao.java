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
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.glowroot.storage.repo.ImmutableServerRollup;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO need to validate cannot have serverIds "A/B/C" and "A/B" since there is logic elsewhere
// (at least in the UI) that "A/B" is only a rollup
public class ServerDao implements ServerRepository {

    private final Session session;

    private final PreparedStatement existsPS;

    private final PreparedStatement insertPS;
    private final PreparedStatement insertProcessInfoPS;
    private final PreparedStatement insertAgentConfigPS;
    private final PreparedStatement readProcessInfoPS;
    private final PreparedStatement readAgentConfigPS;

    private final PreparedStatement updateDetailPS;

    public ServerDao(Session session) {
        this.session = session;

        session.execute("create table if not exists server (one int, server_rollup varchar,"
                + " leaf boolean, primary key (one, server_rollup))");
        session.execute("create table if not exists server_detail (server_id varchar,"
                + " process_info blob, agent_config blob, primary key (server_id))");

        existsPS = session
                .prepare("select server_rollup from server where one = 1 and server_rollup = ?");

        insertPS =
                session.prepare("insert into server (one, server_rollup, leaf) values (1, ?, ?)");
        insertProcessInfoPS = session
                .prepare("insert into server_detail (server_id, process_info) values (?, ?)");
        insertAgentConfigPS = session
                .prepare("insert into server_detail (server_id, agent_config) values (?, ?)");
        readProcessInfoPS =
                session.prepare("select process_info from server_detail where server_id = ?");
        readAgentConfigPS =
                session.prepare("select agent_config from server_detail where server_id = ?");
        updateDetailPS = session.prepare("insert into server_detail (server_id) values (?)");
    }

    @Override
    public List<ServerRollup> readServerRollups() {
        ResultSet results = session.execute("select server_rollup, leaf from server where one = 1");
        List<ServerRollup> rollups = Lists.newArrayList();
        for (Row row : results) {
            String serverRollup = checkNotNull(row.getString(0));
            boolean leaf = row.getBool(1);
            rollups.add(ImmutableServerRollup.of(serverRollup, leaf));
        }
        return rollups;
    }

    // returns stored agent config
    public AgentConfig store(String serverId, ProcessInfo processInfo,
            AgentConfig agentConfig) throws InvalidProtocolBufferException {
        AgentConfig existingAgentConfig = null;
        BoundStatement boundStatement = existsPS.bind();
        boundStatement.setString(0, serverId);
        ResultSet results = session.execute(boundStatement);
        if (results.one() != null) {
            existingAgentConfig = readAgentConfig(serverId);
        }
        boundStatement = insertProcessInfoPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBytes(1, ByteBuffer.wrap(processInfo.toByteArray()));
        session.execute(boundStatement);
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
        boundStatement = insertAgentConfigPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBytes(1, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
        session.execute(boundStatement);
        // insert into server last so readProcessInfo() and readAgentConfig() below are more likely
        // to return non-null
        boundStatement = insertPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBool(1, true);
        session.execute(boundStatement);
        return updatedAgentConfig;
    }

    @Override
    public @Nullable ProcessInfo readProcessInfo(String serverId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readProcessInfoPS.bind();
        boundStatement.setString(0, serverId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // must have just expired or been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from server, but not initial process info data
            return null;
        }
        return ProcessInfo.parseFrom(ByteString.copyFrom(bytes));
    }

    public @Nullable AgentConfig readAgentConfig(String serverId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAgentConfigPS.bind();
        boundStatement.setString(0, serverId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // must have just expired or been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from server, but not initial process info data
            return null;
        }
        return AgentConfig.parseFrom(ByteString.copyFrom(bytes));
    }

    public void storeAgentConfig(String serverId, AgentConfig agentConfig) {
        BoundStatement boundStatement = insertAgentConfigPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setBytes(1, ByteBuffer.wrap(agentConfig.toByteArray()));
        session.execute(boundStatement);
    }

    void updateLastCaptureTime(String serverRollup, boolean leaf) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setBool(1, leaf);
        session.execute(boundStatement);
        boundStatement = updateDetailPS.bind();
        boundStatement.setString(0, serverRollup);
        session.execute(boundStatement);
    }
}
