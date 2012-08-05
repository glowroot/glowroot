/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.ui;

import java.io.IOException;
import java.util.List;

import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.util.RollingFile;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read config data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);

    private final ConfigService configService;
    private final RollingFile rollingFile;
    private final Gson gson = new Gson();

    @Inject
    public ConfigJsonService(ConfigService configService, RollingFile rollingFile) {
        this.configService = configService;
        this.rollingFile = rollingFile;
    }

    public void enableCore() {
        configService.setCoreEnabled(true);
    }

    public void disableCore() {
        configService.setCoreEnabled(false);
    }

    public void enablePlugin(String pluginId) {
        configService.setPluginEnabled(pluginId, true);
    }

    public void disablePlugin(String pluginId) {
        configService.setPluginEnabled(pluginId, false);
    }

    // called dynamically from HttpServer
    public String getConfig() {
        logger.debug("getConfig()");
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList(Iterables.concat(
                Plugins.getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors()));
        double rollingSizeMb = rollingFile.getRollingSizeKb() / 1024.0;
        return "{\"enabled\":" + configService.getCoreConfig().isEnabled()
                + ",\"coreProperties\":" + configService.getCoreConfig().getPropertiesJson()
                + ",\"pluginConfigs\":" + configService.getPluginConfigsJson()
                + ",\"pluginDescriptors\":" + gson.toJson(pluginDescriptors)
                + ",\"actualRollingSizeMb\":" + rollingSizeMb + "}";
    }

    // called dynamically from HttpServer
    public void storeCoreProperties(String properties) {
        logger.debug("storeCoreProperties(): properties={}", properties);
        JsonObject propertiesJson = new JsonParser().parse(properties).getAsJsonObject();
        configService.updateCoreConfig(propertiesJson);
        try {
            // resize() doesn't do anything if the new and old value are the same
            rollingFile.resize(configService.getCoreConfig().getRollingSizeMb() * 1024);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            // TODO return HTTP 500 Internal Server Error?
            return;
        }
    }

    // called dynamically from HttpServer
    public void storePluginProperties(String pluginId, String properties) {
        logger.debug("storePluginProperties(): pluginId={}, properties={}", pluginId, properties);
        JsonObject propertiesJson = new JsonParser().parse(properties).getAsJsonObject();
        configService.storePluginConfig(pluginId, propertiesJson);
    }
}
