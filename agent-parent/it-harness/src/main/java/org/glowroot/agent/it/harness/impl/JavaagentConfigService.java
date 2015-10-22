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

import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceGrpc.ConfigUpdateServiceBlockingClient;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList;
import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.InstrumentationConfig;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate;

class JavaagentConfigService extends ConfigServiceBase {

    private final ConfigUpdateServiceBlockingClient client;

    JavaagentConfigService(ConfigUpdateServiceBlockingClient client) {
        this.client = client;
    }

    @Override
    public void updateTransactionConfig(TransactionConfigUpdate update) {
        client.updateTransactionConfig(update);
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfigUpdate update) {
        client.updateUserRecordingConfig(update);
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfigUpdate update) {
        client.updateAdvancedConfig(update);
    }

    @Override
    public void updatePluginConfig(PluginConfigUpdate update) {
        client.updatePluginConfig(update);
    }

    @Override
    public int updateInstrumentationConfigs(List<InstrumentationConfig> update) {
        ClassUpdateCount classUpdateCount = client.updateInstrumentationConfigs(
                InstrumentationConfigList.newBuilder()
                        .addAllInstrumentationConfig(update)
                        .build());
        return classUpdateCount.getValue();
    }
}
