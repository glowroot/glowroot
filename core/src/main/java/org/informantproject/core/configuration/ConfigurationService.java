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
package org.informantproject.core.configuration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.annotation.Nullable;

import org.informantproject.api.PluginServices.ConfigurationListener;
import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.informantproject.core.configuration.ImmutablePluginConfiguration.PluginConfigurationBuilder;
import org.informantproject.core.configuration.PluginDescriptor.PropertyDescriptor;
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

    private final Set<ConfigurationListener> configurationListeners =
            new CopyOnWriteArraySet<ConfigurationListener>();

    private volatile ImmutableCoreConfiguration coreConfiguration;
    private final Map<String, ImmutablePluginConfiguration> pluginConfigurations;

    private final Object updateLock = new Object();

    @Inject
    public ConfigurationService(ConfigurationDao configurationDao) {
        logger.debug("<init>");
        this.configurationDao = configurationDao;
        // initialize configuration using locally stored values, falling back to defaults if no
        // locally stored values exist
        coreConfiguration = configurationDao.readCoreConfiguration();
        if (coreConfiguration == null) {
            logger.debug("initConfigurations(): default core configuration is being used");
            this.coreConfiguration = new ImmutableCoreConfiguration();
        } else {
            logger.debug("initCoreConfigurations(): core configuration was read from local data"
                    + " store: {}", coreConfiguration);
        }
        pluginConfigurations = Maps.newHashMap();
        // add the "built-in" plugin configuration for the weaving metrics
        pluginConfigurations.put("org.informantproject:informant-core",
                new ImmutablePluginConfiguration(true, new HashMap<String, Object>()));
        Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(Plugins
                .getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors());
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ImmutablePluginConfiguration pluginConfiguration = configurationDao
                    .readPluginConfiguration(pluginDescriptor);
            if (pluginConfiguration != null) {
                pluginConfigurations.put(pluginDescriptor.getId(), pluginConfiguration);
            } else {
                pluginConfigurations.put(pluginDescriptor.getId(), ImmutablePluginConfiguration
                        .create(pluginDescriptor, true));
            }
        }
    }

    public ImmutableCoreConfiguration getCoreConfiguration() {
        return coreConfiguration;
    }

    @Nullable
    public ImmutablePluginConfiguration getPluginConfiguration(String pluginId) {
        ImmutablePluginConfiguration pluginConfiguration = pluginConfigurations.get(pluginId);
        if (pluginConfiguration == null) {
            logger.error("unexpected plugin id '{}', available plugin ids: {}", pluginId, Joiner
                    .on(", ").join(pluginConfigurations.keySet()));
            return ImmutablePluginConfiguration.createDisabledHasNoDescriptor();
        } else {
            return pluginConfiguration;
        }
    }

    public void addConfigurationListener(ConfigurationListener listener) {
        configurationListeners.add(listener);
    }

    public void setCoreEnabled(boolean enabled) {
        configurationDao.setCoreEnabled(enabled);
        synchronized (updateLock) {
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder(coreConfiguration);
            builder.setEnabled(enabled);
            coreConfiguration = builder.build();
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void updateCoreConfiguration(JsonObject propertiesJson) {
        synchronized (updateLock) {
            // copy existing properties
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder(coreConfiguration);
            // overlay updated properties
            builder.setFromJson(propertiesJson);
            coreConfiguration = builder.build();
            configurationDao.storeCoreProperties(coreConfiguration.getPropertiesJson());
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    public void setPluginEnabled(String pluginId, boolean enabled) {
        configurationDao.setPluginEnabled(pluginId, enabled);
        synchronized (updateLock) {
            PluginConfigurationBuilder builder = new PluginConfigurationBuilder(Plugins
                    .getDescriptor(pluginId), pluginConfigurations.get(pluginId));
            builder.setEnabled(enabled);
            pluginConfigurations.put(pluginId, builder.build());
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void storePluginConfiguration(String pluginId, JsonObject propertiesJson) {
        synchronized (updateLock) {
            // start with existing plugin configuration
            PluginDescriptor pluginDescriptor = Plugins.getDescriptor(pluginId);
            PluginConfigurationBuilder builder = new PluginConfigurationBuilder(pluginDescriptor,
                    pluginConfigurations.get(pluginId));
            // overlay updated properties
            builder.setProperties(propertiesJson);
            ImmutablePluginConfiguration pluginConfiguration = builder.build();
            // only store non-hidden properties
            configurationDao.storePluginProperties(pluginId, pluginConfiguration
                    .getNonHiddenPropertiesJson(pluginDescriptor));
            pluginConfigurations.put(pluginId, pluginConfiguration);
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    public String getPluginConfigurationJson() {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            for (PluginDescriptor pluginDescriptor : Iterables.concat(Plugins
                    .getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors())) {
                jw.name(pluginDescriptor.getId());
                jw.beginObject();
                ImmutablePluginConfiguration pluginConfiguration = pluginConfigurations.get(
                        pluginDescriptor.getId());
                jw.name("enabled");
                jw.value(pluginConfiguration.isEnabled());
                jw.name("properties");
                jw.beginObject();
                writeProperties(pluginDescriptor, pluginConfiguration, jw);
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

    private void notifyConfigurationListeners() {
        for (ConfigurationListener configurationListener : configurationListeners) {
            configurationListener.onChange();
        }
    }

    private void writeProperties(PluginDescriptor pluginDescriptor,
            ImmutablePluginConfiguration pluginConfiguration, JsonWriter jw) throws IOException {

        for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
            if (property.isHidden()) {
                continue;
            }
            jw.name(property.getName());
            if (property.getType().equals("string")) {
                String value = pluginConfiguration.getStringProperty(property.getName());
                if (value == null) {
                    jw.nullValue();
                } else {
                    jw.value(value);
                }
            } else if (property.getType().equals("boolean")) {
                jw.value(pluginConfiguration.getBooleanProperty(property.getName()));
            } else if (property.getType().equals("double")) {
                Double value = pluginConfiguration.getDoubleProperty(property.getName());
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
