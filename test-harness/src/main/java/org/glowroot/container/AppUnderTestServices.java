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
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class AppUnderTestServices {

    public static AppUnderTestServices get() {
        return new AppUnderTestServices();
    }

    private AppUnderTestServices() {}

    public void setPluginEnabled(String pluginId, boolean enabled) throws Exception {
        ConfigService configService = getConfigService();
        PluginConfig config = configService.getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        PluginConfig updatedConfig = config.withEnabled(enabled);
        configService.updatePluginConfig(updatedConfig, config.version());
    }

    private static ConfigService getConfigService() {
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        checkNotNull(glowrootModule);
        ConfigModule configModule = glowrootModule.getConfigModule();
        return configModule.getConfigService();
    }
}
