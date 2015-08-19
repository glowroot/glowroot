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
package org.glowroot.agent.config;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.Config;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserInterfaceConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.ImmutableRollupConfig;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.plugin.api.config.ConfigListener;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    // 5 seconds
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    // 1 minute
    private static final long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000);
    // 5 minutes
    private static final long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", 5 * 60 * 1000);
    // 30 minutes
    private static final long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", 30 * 60 * 1000);

    private final ConfigFile configFile;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener>create());

    private final ImmutableList<RollupConfig> rollupConfigs;

    private volatile Config config;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    // memory barrier is used to ensure memory visibility of config values
    private volatile boolean memoryBarrier;

    public ConfigService(File baseDir, List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(new File(baseDir, "config.json"), pluginDescriptors);
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        rollupConfigs = ImmutableList.<RollupConfig>of(
                // default rollup level #0 fixed interval is 1 minute,
                // making default view threshold 15 min
                ImmutableRollupConfig.of(ROLLUP_0_INTERVAL_MILLIS, ROLLUP_0_INTERVAL_MILLIS * 15),
                // default rollup level #1 fixed interval is 5 minutes,
                // making default view threshold 1 hour
                ImmutableRollupConfig.of(ROLLUP_1_INTERVAL_MILLIS, ROLLUP_1_INTERVAL_MILLIS * 12),
                // default rollup level #2 fixed interval is 30 minutes,
                // making default view threshold 8 hour
                ImmutableRollupConfig.of(ROLLUP_2_INTERVAL_MILLIS, ROLLUP_2_INTERVAL_MILLIS * 16));
        try {
            config = configFile.loadConfig();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            config = configFile.getDefaultConfig();
        }
        for (InstrumentationConfig instrumentationConfig : config.instrumentationConfigs()) {
            ImmutableList<String> errors = instrumentationConfig.validationErrors();
            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Invalid instrumentation config: ");
                sb.append(Joiner.on(", ").join(errors));
                sb.append(" ");
                try {
                    sb.append(ObjectMappers.create().writeValueAsString(instrumentationConfig));
                } catch (JsonProcessingException e) {
                    logger.error(e.getMessage(), e);
                }
                logger.error(sb.toString());
            }
        }
    }

    public Object getWriteLock() {
        return writeLock;
    }

    public TransactionConfig getTransactionConfig() {
        return config.transactionConfig();
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        return config.userInterfaceConfig();
    }

    public StorageConfig getStorageConfig() {
        return config.storageConfig();
    }

    public SmtpConfig getSmtpConfig() {
        return config.smtpConfig();
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return config.userRecordingConfig();
    }

    public AdvancedConfig getAdvancedConfig() {
        return config.advancedConfig();
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : config.pluginConfigs()) {
            if (pluginId.equals(pluginConfig.id())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public List<InstrumentationConfig> getInstrumentationConfigs() {
        return config.instrumentationConfigs();
    }

    public List<GaugeConfig> getGaugeConfigs() {
        return config.gaugeConfigs();
    }

    public List<AlertConfig> getAlertConfigs() {
        return config.alertConfigs();
    }

    public long getGaugeCollectionIntervalMillis() {
        return GAUGE_COLLECTION_INTERVAL_MILLIS;
    }

    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    public String getDefaultDisplayedTransactionType() {
        String defaultDisplayedTransactionType =
                config.transactionConfig().defaultDisplayedTransactionType();
        if (!defaultDisplayedTransactionType.isEmpty()) {
            return defaultDisplayedTransactionType;
        }
        return configFile.getDefaultDisplayedTransactionType(config.instrumentationConfigs());
    }

    public ImmutableList<String> getAllTransactionTypes() {
        Set<String> transactionTypes = Sets.newLinkedHashSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig pluginConfig = getPluginConfig(pluginDescriptor.id());
            if (pluginConfig != null && pluginConfig.enabled()) {
                transactionTypes.addAll(pluginDescriptor.transactionTypes());
                addInstrumentationTransactionTypes(pluginDescriptor.instrumentationConfigs(),
                        transactionTypes, pluginConfig);
            }
        }
        addInstrumentationTransactionTypes(getInstrumentationConfigs(), transactionTypes, null);
        return ImmutableList.copyOf(transactionTypes);
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
        listener.onChange();
    }

    public void addPluginConfigListener(String pluginId, ConfigListener listener) {
        pluginConfigListeners.put(pluginId, listener);
    }

    public Config getConfig() {
        return config;
    }

    // must be holding writeLock
    public void updateConfig(Config updatedConfig) throws IOException {
        configFile.write(updatedConfig);
        config = updatedConfig;
        notifyConfigListeners();
    }

    public boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    public void writeMemoryBarrier() {
        memoryBarrier = true;
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    public void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    public void notifyPluginConfigListeners(String pluginId) {
        // make copy first to avoid possible ConcurrentModificationException while iterating
        Collection<ConfigListener> listeners =
                ImmutableList.copyOf(pluginConfigListeners.get(pluginId));
        for (ConfigListener listener : listeners) {
            listener.onChange();
        }
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        configFile.delete();
        config = configFile.loadConfig();
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    private static void addInstrumentationTransactionTypes(List<InstrumentationConfig> configs,
            Set<String> transactionTypes, @Nullable PluginConfig pluginConfig) {
        for (InstrumentationConfig config : configs) {
            String transactionType = config.transactionType();
            if (transactionType.isEmpty()) {
                continue;
            }
            if (pluginConfig == null || isEnabled(config, pluginConfig)) {
                transactionTypes.add(transactionType);
            }
        }
    }

    private static boolean isEnabled(InstrumentationConfig config, PluginConfig pluginConfig) {
        return isEnabled(config.enabledProperty(), pluginConfig)
                && isEnabled(config.traceEntryEnabledProperty(), pluginConfig);
    }

    private static boolean isEnabled(String propertyName, PluginConfig pluginConfig) {
        return propertyName.isEmpty() || pluginConfig.getBooleanProperty(propertyName);
    }

    private void notifyAllPluginConfigListeners() {
        // make copy first to avoid possible ConcurrentModificationException while iterating
        Collection<ConfigListener> listeners = ImmutableList.copyOf(pluginConfigListeners.values());
        for (ConfigListener configListener : listeners) {
            configListener.onChange();
        }
    }
}
