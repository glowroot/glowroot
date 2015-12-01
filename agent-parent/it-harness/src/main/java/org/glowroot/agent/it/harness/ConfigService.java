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
package org.glowroot.agent.it.harness;

import java.util.List;

import org.glowroot.wire.api.model.ConfigOuterClass.Config.AdvancedConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.InstrumentationConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.PluginConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.TransactionConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.UserRecordingConfig;

public interface ConfigService {

    void updateTransactionConfig(TransactionConfig config) throws Exception;

    void updateUserRecordingConfig(UserRecordingConfig config) throws Exception;

    void updateAdvancedConfig(AdvancedConfig config) throws Exception;

    void updatePluginConfig(PluginConfig config) throws Exception;

    // returns the number of classes updated during re-weaving
    int updateInstrumentationConfigs(List<InstrumentationConfig> configs) throws Exception;

    // convenience methods wrapping updatePluginConfig()
    void disablePlugin(String pluginId) throws Exception;

    // convenience methods wrapping updatePluginConfig()
    void setPluginProperty(String pluginId, String propertyName, boolean propertyValue)
            throws Exception;

    // convenience methods wrapping updatePluginConfig()
    void setPluginProperty(String pluginId, String propertyName, Double propertyValue)
            throws Exception;

    // convenience methods wrapping updatePluginConfig()
    void setPluginProperty(String pluginId, String propertyName, String propertyValue)
            throws Exception;
}
