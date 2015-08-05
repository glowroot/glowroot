/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.transaction;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.plugin.api.config.BooleanProperty;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.plugin.api.config.DoubleProperty;
import org.glowroot.plugin.api.config.StringProperty;

import static com.google.common.base.Preconditions.checkNotNull;

class ConfigServiceImpl implements org.glowroot.plugin.api.config.ConfigService, ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(ConfigServiceImpl.class);

    private final ConfigService configService;

    // pluginId is either the id of a registered plugin or it is null
    // (see validation in constructor)
    private final @Nullable String pluginId;

    // cache for fast read access
    // visibility is provided by memoryBarrier below
    private boolean enabled;
    private @MonotonicNonNull PluginConfig pluginConfig;

    private final Map<ConfigListener, Boolean> weakConfigListeners =
            new MapMaker().weakKeys().makeMap();

    static ConfigServiceImpl create(ConfigService configService,
            List<PluginDescriptor> pluginDescriptors, String pluginId) {
        ConfigServiceImpl configServiceImpl =
                new ConfigServiceImpl(configService, pluginDescriptors, pluginId);
        configService.addPluginConfigListener(pluginId, configServiceImpl);
        configService.addConfigListener(configServiceImpl);
        return configServiceImpl;
    }

    private ConfigServiceImpl(ConfigService configService, List<PluginDescriptor> pluginDescriptors,
            String pluginId) {
        this.configService = configService;
        PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            List<String> ids = Lists.newArrayList();
            for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                ids.add(pluginDescriptor.id());
            }
            logger.warn("unexpected plugin id: {} (available plugin ids are {})", pluginId,
                    Joiner.on(", ").join(ids));
            this.pluginId = null;
        } else {
            this.pluginId = pluginId;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public StringProperty getStringProperty(String name) {
        if (name == null) {
            logger.error("getStringProperty(): argument 'name' must be non-null");
            return new StringPropertyImpl("");
        }
        StringPropertyImpl stringProperty = new StringPropertyImpl(name);
        weakConfigListeners.put(stringProperty, true);
        return stringProperty;
    }

    @Override
    public BooleanProperty getBooleanProperty(String name) {
        if (name == null) {
            logger.error("getBooleanProperty(): argument 'name' must be non-null");
            return new BooleanPropertyImpl("");
        }
        BooleanPropertyImpl booleanProperty = new BooleanPropertyImpl(name);
        weakConfigListeners.put(booleanProperty, true);
        return booleanProperty;
    }

    @Override
    public DoubleProperty getDoubleProperty(String name) {
        if (name == null) {
            logger.error("getDoubleProperty(): argument 'name' must be non-null");
            return new DoublePropertyImpl("");
        }
        DoublePropertyImpl doubleProperty = new DoublePropertyImpl(name);
        weakConfigListeners.put(doubleProperty, true);
        return doubleProperty;
    }

    @Override
    public BooleanProperty getEnabledProperty(String name) {
        if (name == null) {
            logger.error("getEnabledProperty(): argument 'name' must be non-null");
            return new BooleanPropertyImpl("");
        }
        EnabledPropertyImpl enabledProperty = new EnabledPropertyImpl(name);
        weakConfigListeners.put(enabledProperty, true);
        return enabledProperty;
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        if (pluginId == null) {
            return;
        }
        if (listener == null) {
            logger.error("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
        listener.onChange();
    }

    @Override
    public void onChange() {
        if (pluginId == null) {
            enabled = true;
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            // pluginConfig should not be null since pluginId was already validated
            // at construction time and plugins cannot be removed (or their ids changed) at runtime
            checkNotNull(pluginConfig);
            enabled = pluginConfig.enabled();
            this.pluginConfig = pluginConfig;
        }
        for (ConfigListener weakConfigListener : weakConfigListeners.keySet()) {
            weakConfigListener.onChange();
        }
        configService.writeMemoryBarrier();
    }

    private class StringPropertyImpl implements StringProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private String value = "";
        private StringPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getStringProperty(name);
            }
        }
        @Override
        public String value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getStringProperty(name);
            }
        }
    }

    private class BooleanPropertyImpl implements BooleanProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private boolean value;
        private BooleanPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getBooleanProperty(name);
            }
        }
        @Override
        public boolean value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getBooleanProperty(name);
            }
        }
    }

    private class DoublePropertyImpl implements DoubleProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private @Nullable Double value;
        private DoublePropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getDoubleProperty(name);
            }
        }
        @Override
        public @Nullable Double value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getDoubleProperty(name);
            }
        }
    }

    private class EnabledPropertyImpl implements BooleanProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private boolean value;
        private EnabledPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = enabled && pluginConfig.getBooleanProperty(name);
            }
        }
        @Override
        public boolean value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = enabled && pluginConfig.getBooleanProperty(name);
            }
        }
    }
}
