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

import java.io.IOException;
import java.util.List;

import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.InstrumentationConfig;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate;

class LocalConfigService extends ConfigServiceBase {

    private final ConfigUpdateServiceHelper helper;

    LocalConfigService(ConfigUpdateServiceHelper helper) {
        this.helper = helper;
    }

    @Override
    public void updateTransactionConfig(TransactionConfigUpdate update) throws IOException {
        helper.updateTransactionConfig(update);
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfigUpdate update) throws IOException {
        helper.updateUserRecordingConfig(update);
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfigUpdate update) throws IOException {
        helper.updateAdvancedConfig(update);
    }

    @Override
    public void updatePluginConfig(PluginConfigUpdate update) throws IOException {
        helper.updatePluginConfig(update);
    }

    @Override
    public int updateInstrumentationConfigs(List<InstrumentationConfig> update) throws Exception {
        return helper.updateInstrumentationConfigs(update);
    }
}
