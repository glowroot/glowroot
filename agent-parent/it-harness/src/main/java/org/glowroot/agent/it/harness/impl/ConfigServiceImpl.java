/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.wire.api.model.ConfigOuterClass.Config;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.AdvancedConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.InstrumentationConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.InstrumentationConfigList;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.OptionalBool;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.PluginConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.PluginProperty;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.TransactionConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.UserRecordingConfig;

class ConfigServiceImpl implements ConfigService {

    private final GrpcServerWrapper server;
    private final boolean reweavable;

    ConfigServiceImpl(GrpcServerWrapper server, boolean reweavable) {
        this.server = server;
        this.reweavable = reweavable;
    }

    @Override
    public void updateTransactionConfig(TransactionConfig config) throws Exception {
        server.updateConfig(Config.newBuilder()
                .setTransactionConfig(config)
                .build());
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        server.updateConfig(Config.newBuilder()
                .setUserRecordingConfig(config)
                .build());
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        server.updateConfig(Config.newBuilder()
                .setAdvancedConfig(config)
                .build());
    }

    @Override
    public void updatePluginConfig(PluginConfig config) throws Exception {
        server.updateConfig(Config.newBuilder()
                .addPluginConfig(config)
                .build());
    }

    @Override
    public int updateInstrumentationConfigs(List<InstrumentationConfig> configs) throws Exception {
        server.updateConfig(Config.newBuilder()
                .setInstrumentationConfigList(InstrumentationConfigList.newBuilder()
                        .addAllInstrumentationConfig(configs))
                .build());
        if (reweavable) {
            return server.reweave();
        } else {
            return 0;
        }
    }

    @Override
    public void disablePlugin(String pluginId) throws Exception {
        updatePluginConfig(PluginConfig.newBuilder()
                .setId(pluginId)
                .setEnabled(OptionalBool.newBuilder().setValue(false))
                .build());
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, boolean propertyValue)
            throws Exception {
        updatePluginConfig(PluginConfig.newBuilder()
                .setId(pluginId)
                .addProperty(PluginProperty.newBuilder()
                        .setName(propertyName)
                        .setBval(propertyValue))
                .build());
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Double propertyValue) throws Exception {
        if (propertyValue == null) {
            updatePluginConfig(PluginConfig.newBuilder()
                    .setId(pluginId)
                    .addProperty(PluginProperty.newBuilder()
                            .setName(propertyName)
                            .setDvalNull(true))
                    .build());
        } else {
            updatePluginConfig(PluginConfig.newBuilder()
                    .setId(pluginId)
                    .addProperty(PluginProperty.newBuilder()
                            .setName(propertyName)
                            .setDval(propertyValue))
                    .build());
        }
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, String propertyValue)
            throws Exception {
        updatePluginConfig(PluginConfig.newBuilder()
                .setId(pluginId)
                .addProperty(PluginProperty.newBuilder()
                        .setName(propertyName)
                        .setSval(propertyValue))
                .build());
    }
}
