/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.container;

import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.common.config.ImmutablePluginConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.local.LocalModule;

import static com.google.common.base.Preconditions.checkNotNull;

public class AppUnderTestServices {

    public static AppUnderTestServices get() {
        return new AppUnderTestServices();
    }

    private AppUnderTestServices() {}

    public void setPluginEnabled(String pluginId, boolean enabled) throws Exception {
        ConfigRepository configRepository = getConfigRepository();
        PluginConfig config = configRepository.getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        PluginConfig updatedConfig =
                ImmutablePluginConfig.builder().copyFrom(config).enabled(enabled).build();
        configRepository.updatePluginConfig(updatedConfig, config.version());
    }

    private static ConfigRepository getConfigRepository() {
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        checkNotNull(glowrootModule);
        LocalModule storageModule = glowrootModule.getLocalModule();
        return storageModule.getConfigRepository();
    }
}
