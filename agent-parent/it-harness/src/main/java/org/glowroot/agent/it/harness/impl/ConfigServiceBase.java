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

import javax.annotation.Nullable;

import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.it.harness.model.ConfigUpdate.OptionalBool;
import org.glowroot.agent.it.harness.model.ConfigUpdate.OptionalDouble;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginProperty;

abstract class ConfigServiceBase implements ConfigService {

    @Override
    public void disablePlugin(String pluginId) throws Exception {
        updatePluginConfig(PluginConfigUpdate.newBuilder()
                .setId(pluginId)
                .setEnabled(OptionalBool.newBuilder().setValue(false))
                .build());
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, boolean propertyValue)
            throws Exception {
        updatePluginConfig(PluginConfigUpdate.newBuilder()
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
            updatePluginConfig(PluginConfigUpdate.newBuilder()
                    .setId(pluginId)
                    .addProperty(PluginProperty.newBuilder()
                            .setName(propertyName)
                            .setDval(OptionalDouble.newBuilder().setAbsent(true)))
                    .build());
        } else {
            updatePluginConfig(PluginConfigUpdate.newBuilder()
                    .setId(pluginId)
                    .addProperty(PluginProperty.newBuilder()
                            .setName(propertyName)
                            .setDval(OptionalDouble.newBuilder().setValue(propertyValue)))
                    .build());
        }
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, String propertyValue)
            throws Exception {
        updatePluginConfig(PluginConfigUpdate.newBuilder()
                .setId(pluginId)
                .addProperty(PluginProperty.newBuilder()
                        .setName(propertyName)
                        .setSval(propertyValue))
                .build());
    }
}
