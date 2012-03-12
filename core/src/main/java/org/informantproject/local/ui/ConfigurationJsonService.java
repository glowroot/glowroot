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

import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.PluginDescriptor;
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
 * Json service to read configuration data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigurationJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationJsonService.class);

    private final ConfigurationService configurationService;
    private final RollingFile rollingFile;

    @Inject
    public ConfigurationJsonService(ConfigurationService configurationService,
            RollingFile rollingFile) {

        this.configurationService = configurationService;
        this.rollingFile = rollingFile;
    }

    public void enableCore() {
        configurationService.setCoreEnabled(true);
    }

    public void disableCore() {
        configurationService.setCoreEnabled(false);
    }

    public void enablePlugin(String pluginId) {
        configurationService.setPluginEnabled(pluginId, true);
    }

    public void disablePlugin(String pluginId) {
        configurationService.setPluginEnabled(pluginId, false);
    }

    // called dynamically from HttpServer
    public String getConfiguration() {
        logger.debug("getConfiguration()");
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList(Iterables.concat(
                configurationService.getPackagedPluginDescriptors(),
                configurationService.getInstalledPluginDescriptors()));
        double rollingSizeMb = rollingFile.getRollingSizeKb() / 1024;
        return "{\"enabled\":" + configurationService.getCoreConfiguration().isEnabled()
                + ",\"coreConfiguration\":" + configurationService.getCoreConfiguration()
                        .getPropertiesJson() + ",\"pluginConfiguration\":" + configurationService
                        .getPluginConfigurationJson() + ",\"pluginDescriptors\":" + new Gson()
                        .toJson(pluginDescriptors) + ",\"actualRollingSizeMb\":" + rollingSizeMb
                + "}";
    }

    // called dynamically from HttpServer
    public void storeCoreProperties(String properties) {
        logger.debug("storeCoreProperties(): properties={}", properties);
        JsonObject propertiesJson = new JsonParser().parse(properties).getAsJsonObject();
        configurationService.updateCoreConfiguration(propertiesJson);
        try {
            // resize() doesn't do anything if the new and old value are the same
            rollingFile.resize(configurationService.getCoreConfiguration().getRollingSizeMb()
                    * 1024);
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
        configurationService.storePluginConfiguration(pluginId, propertiesJson);
    }
}
