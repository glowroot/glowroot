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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public void updateCoreConfiguration(String message) {
        logger.debug("updateCoreConfiguration(): message={}", message);
        JsonObject messageJson = new JsonParser().parse(message).getAsJsonObject();
        CoreConfigurationBuilder builder = new CoreConfigurationBuilder(coreConfiguration);
        if (messageJson.get("enabled") != null) {
            builder.setEnabled(messageJson.get("enabled").getAsBoolean());
        }
        if (messageJson.get("thresholdMillis") != null) {
            builder.setThresholdMillis(messageJson.get("thresholdMillis").getAsInt());
        }
        if (messageJson.get("stuckThresholdMillis") != null) {
            builder.setStuckThresholdMillis(messageJson.get("stuckThresholdMillis").getAsInt());
        }
        if (messageJson.get("stackTraceInitialDelayMillis") != null) {
            builder.setStackTraceInitialDelayMillis(messageJson.get("stackTraceInitialDelayMillis")
                    .getAsInt());
        }
        if (messageJson.get("stackTracePeriodMillis") != null) {
            builder.setStackTracePeriodMillis(messageJson.get("stackTracePeriodMillis").getAsInt());
        }
        if (messageJson.get("spanStackTraceThresholdMillis") != null) {
            builder.setSpanStackTraceThresholdMillis(messageJson.get(
                    "spanStackTraceThresholdMillis").getAsInt());
        }
        if (messageJson.get("maxSpansPerTrace") != null) {
            builder.setMaxSpansPerTrace(messageJson.get("maxSpansPerTrace").getAsInt());
        }
        if (messageJson.get("rollingSizeMb") != null) {
            builder.setRollingSizeMb(messageJson.get("rollingSizeMb").getAsInt());
        }
        if (messageJson.get("warnOnSpanOutsideTrace") != null) {
            builder.setWarnOnSpanOutsideTrace(messageJson.get("warnOnSpanOutsideTrace")
                    .getAsBoolean());
        }
        if (messageJson.get("metricPeriodMillis") != null) {
            builder.setMetricPeriodMillis(messageJson.get("metricPeriodMillis").getAsInt());
        }
        coreConfiguration = builder.build();
        configurationDao.storeCoreConfiguration(coreConfiguration);
        notifyCoreConfigurationListeners();
    }

    public void updatePluginConfiguration(ImmutablePluginConfiguration pluginConfiguration) {
        this.pluginConfiguration = pluginConfiguration;
        configurationDao.storePluginConfiguration(pluginConfiguration);
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
