/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.informantproject.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Stateful singleton service for accessing and updating configuration objects. Configuration
 * objects are cached for performance. Also, listeners can be registered with this service in order
 * to receive notifications when configuration objects are updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigurationService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConfigurationDao configurationDao;
    private final Set<CoreConfigurationListener> coreConfigurationListeners =
            new CopyOnWriteArraySet<CoreConfigurationListener>();

    private volatile ImmutableCoreConfiguration coreConfiguration;
    private volatile ImmutablePluginConfiguration pluginConfiguration;

    @Inject
    public ConfigurationService(ConfigurationDao configurationDao) {
        this.configurationDao = configurationDao;
        initConfigurations();
    }

    public ImmutableCoreConfiguration getCoreConfiguration() {
        return coreConfiguration;
    }

    public ImmutablePluginConfiguration getPluginConfiguration() {
        return pluginConfiguration;
    }

    public void addCoreConfigurationListener(CoreConfigurationListener listener) {
        coreConfigurationListeners.add(listener);
    }

    public void updateConfiguration(String message) {
        logger.debug("updateConfiguration(): message={}", message);
        JsonObject messageJson = new JsonParser().parse(message).getAsJsonObject();
        JsonElement coreConfigurationJson = messageJson.get("coreConfiguration");
        if (coreConfigurationJson != null) {
            coreConfiguration = new Gson().fromJson(coreConfigurationJson,
                    CoreConfigurationBuilder.class).build();
            configurationDao.storeCoreConfiguration(coreConfiguration);
            notifyCoreConfigurationListeners();
        }
        JsonElement pluginConfigurationJson = messageJson.get("pluginConfiguration");
        if (pluginConfigurationJson != null) {
            pluginConfiguration = ImmutablePluginConfiguration.fromJson(pluginConfigurationJson
                    .getAsJsonObject());
            configurationDao.storePluginConfiguration(pluginConfiguration);
        }
    }

    private void notifyCoreConfigurationListeners() {
        for (CoreConfigurationListener coreConfigurationListener : coreConfigurationListeners) {
            coreConfigurationListener.onChange(coreConfiguration);
        }
    }

    private void initConfigurations() {
        // initialize configuration using locally stored values, falling back to defaults if no
        // locally stored values exist
        coreConfiguration = configurationDao.readCoreConfiguration();
        if (coreConfiguration == null) {
            logger.debug("initConfigurations(): default core configuration is being used");
            coreConfiguration = new ImmutableCoreConfiguration();
        } else {
            logger.debug("initConfigurations(): core configuration"
                    + " was read from local data store: {}", coreConfiguration);
        }
        // init plugin configuration
        pluginConfiguration = configurationDao.readPluginConfiguration();
        if (pluginConfiguration == null) {
            logger.debug("initConfigurations(): default plugin configuration is being used");
            pluginConfiguration = new ImmutablePluginConfiguration(
                    new HashMap<String, Map<String, Object>>());
        } else {
            logger.debug("initConfigurations(): plugin configuration"
                    + " was read from local data store: {}", pluginConfiguration);
        }
    }

    public interface CoreConfigurationListener {
        void onChange(ImmutableCoreConfiguration updatedConfiguration);
    }
}
