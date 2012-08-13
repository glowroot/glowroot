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
package org.informantproject.core.config;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.informantproject.api.PluginServices.ConfigListener;
import org.informantproject.core.config.PluginDescriptor.PropertyDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Stateful singleton service for accessing and updating config objects. Config objects are cached
 * for performance. Also, listeners can be registered with this service in order to receive
 * notifications when config objects are updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigDao configDao;

    private final Set<ConfigListener> configListeners = new CopyOnWriteArraySet<ConfigListener>();

    private volatile CoreConfig coreConfig;
    private final Map<String, PluginConfig> pluginConfigs;

    private final Object updateLock = new Object();

    @Inject
    ConfigService(ConfigDao configDao) {
        logger.debug("<init>()");
        this.configDao = configDao;
        // initialize config using locally stored values, falling back to defaults if no locally
        // stored values exist
        CoreConfig coreConfig = configDao.readCoreConfig();
        if (coreConfig == null) {
            logger.debug("<init>(): default core config is being used");
            this.coreConfig = CoreConfig.getDefaultInstance();
        } else {
            logger.debug("<init>(): core config was read from local data store: {}", coreConfig);
            this.coreConfig = coreConfig;
        }
        pluginConfigs = Maps.newHashMap();
        // add the "built-in" plugin config for the weaving metrics
        pluginConfigs.put("org.informantproject:informant-core", PluginConfig.getEnabledInstance());
        Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(
                Plugins.getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors());
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig pluginConfig = configDao.readPluginConfig(pluginDescriptor);
            if (pluginConfig != null) {
                pluginConfigs.put(pluginDescriptor.getId(), pluginConfig);
            } else {
                pluginConfigs.put(pluginDescriptor.getId(), PluginConfig.builder(pluginDescriptor)
                        .setEnabled(true).build());
            }
        }
    }

    public CoreConfig getCoreConfig() {
        return coreConfig;
    }

    public PluginConfig getPluginConfig(String pluginId) {
        PluginConfig pluginConfig = pluginConfigs.get(pluginId);
        if (pluginConfig == null) {
            logger.error("unexpected plugin id '{}', available plugin ids: {}", pluginId, Joiner
                    .on(", ").join(pluginConfigs.keySet()));
            return PluginConfig.getDisabledInstance();
        } else {
            return pluginConfig;
        }
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    public void setCoreEnabled(boolean enabled) {
        configDao.setCoreEnabled(enabled);
        synchronized (updateLock) {
            coreConfig = CoreConfig.builder()
                    .copy(coreConfig)
                    .enabled(enabled)
                    .build();
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated config is not passed to the listeners so there shouldn't be a problem even if
        // notifications happen to get sent out of order
        notifyConfigListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void updateCoreConfig(JsonObject propertiesJson) {
        synchronized (updateLock) {
            coreConfig = CoreConfig.builder()
                    // copy existing properties
                    .copy(coreConfig)
                    // overlay updated properties
                    .setFromJson(propertiesJson)
                    .build();
            configDao.storeCoreProperties(coreConfig.getPropertiesJson());
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated config is not passed to the listeners so there shouldn't be a problem even if
        // notifications happen to get sent out of order
        notifyConfigListeners();
    }

    public void setPluginEnabled(String pluginId, boolean enabled) {
        configDao.setPluginEnabled(pluginId, enabled);
        synchronized (updateLock) {
            PluginDescriptor pluginDescriptor = Plugins.getDescriptor(pluginId);
            PluginConfig pluginConfig = PluginConfig.builder(pluginDescriptor)
                    .copy(pluginConfigs.get(pluginId))
                    .setEnabled(enabled)
                    .build();
            pluginConfigs.put(pluginId, pluginConfig);
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated config is not passed to the listeners so there shouldn't be a problem even if
        // notifications happen to get sent out of order
        notifyConfigListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void storePluginConfig(String pluginId, JsonObject propertiesJson) {
        synchronized (updateLock) {
            PluginDescriptor pluginDescriptor = Plugins.getDescriptor(pluginId);
            PluginConfig pluginConfig = PluginConfig.builder(pluginDescriptor)
                    // start with existing plugin config
                    .copy(pluginConfigs.get(pluginId))
                    // overlay updated properties
                    .setProperties(propertiesJson)
                    .build();
            // only store non-hidden properties
            configDao.storePluginProperties(pluginId,
                    pluginConfig.getNonHiddenPropertiesJson(pluginDescriptor));
            pluginConfigs.put(pluginId, pluginConfig);
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated config is not passed to the listeners so there shouldn't be a problem even if
        // notifications happen to get sent out of order
        notifyConfigListeners();
    }

    public String getPluginConfigsJson() {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(
                    Plugins.getPackagedPluginDescriptors(),
                    Plugins.getInstalledPluginDescriptors());
            for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                jw.name(pluginDescriptor.getId());
                jw.beginObject();
                PluginConfig pluginConfig = pluginConfigs.get(pluginDescriptor.getId());
                jw.name("enabled");
                jw.value(pluginConfig.isEnabled());
                jw.name("properties");
                jw.beginObject();
                writeProperties(pluginDescriptor, pluginConfig, jw);
                jw.endObject();
                jw.endObject();
            }
            jw.endObject();
            jw.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return sb.toString();
    }

    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void writeProperties(PluginDescriptor pluginDescriptor, PluginConfig pluginConfig,
            JsonWriter jw) throws IOException {

        for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
            if (property.isHidden()) {
                continue;
            }
            jw.name(property.getName());
            if (property.getType().equals("string")) {
                String value = pluginConfig.getStringProperty(property.getName());
                if (value == null) {
                    jw.nullValue();
                } else {
                    jw.value(value);
                }
            } else if (property.getType().equals("boolean")) {
                jw.value(pluginConfig.getBooleanProperty(property.getName()));
            } else if (property.getType().equals("double")) {
                Double value = pluginConfig.getDoubleProperty(property.getName());
                if (value == null) {
                    jw.nullValue();
                } else {
                    jw.value(value);
                }
            } else {
                logger.error("unexpected type '" + property.getType() + "', this should have"
                        + " been caught by schema validation");
            }
        }
    }
}
