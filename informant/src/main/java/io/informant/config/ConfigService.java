/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.PluginServices.ConfigListener;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.Singleton;

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

    private final PluginDescriptorCache pluginDescriptorCache;
    private final File configFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener>create());

    private volatile Config config;

    ConfigService(File dataDir, PluginDescriptorCache pluginDescriptorCache) {
        logger.debug("<init>()");
        this.pluginDescriptorCache = pluginDescriptorCache;
        configFile = new File(dataDir, "config.json");
        try {
            config = loadConfig(configFile, pluginDescriptorCache);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            config = Config.getDefault(pluginDescriptorCache.getPluginDescriptors());
        }
    }

    public GeneralConfig getGeneralConfig() {
        return config.getGeneralConfig();
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        return config.getCoarseProfilingConfig();
    }

    public FineProfilingConfig getFineProfilingConfig() {
        return config.getFineProfilingConfig();
    }

    public UserOverridesConfig getUserOverridesConfig() {
        return config.getUserOverridesConfig();
    }

    public StorageConfig getStorageConfig() {
        return config.getStorageConfig();
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        return config.getUserInterfaceConfig();
    }

    public AdvancedConfig getAdvancedConfig() {
        return config.getAdvancedConfig();
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : config.getPluginConfigs()) {
            if (pluginId.equals(pluginConfig.getId())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    public void addPluginConfigListener(String pluginId, ConfigListener listener) {
        pluginConfigListeners.put(pluginId, listener);
    }

    public String updateGeneralConfig(GeneralConfig generalConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        boolean notifyPluginConfigListeners;
        synchronized (writeLock) {
            if (!config.getGeneralConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            boolean previousEnabled = config.getGeneralConfig().isEnabled();
            Config updatedConfig = Config.builder(config)
                    .generalConfig(generalConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
            notifyPluginConfigListeners = config.getGeneralConfig().isEnabled() != previousEnabled;
        }
        notifyConfigListeners();
        if (notifyPluginConfigListeners) {
            notifyAllPluginConfigListeners();
        }
        return generalConfig.getVersion();
    }

    public String updateCoarseProfilingConfig(CoarseProfilingConfig coarseProfilingConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getCoarseProfilingConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .coarseProfilingConfig(coarseProfilingConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return coarseProfilingConfig.getVersion();
    }

    public String updateFineProfilingConfig(FineProfilingConfig fineProfilingConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getFineProfilingConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .fineProfilingConfig(fineProfilingConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return fineProfilingConfig.getVersion();
    }

    public String updateUserOverridesConfig(UserOverridesConfig userOverridesConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getUserOverridesConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .userOverridesConfig(userOverridesConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userOverridesConfig.getVersion();
    }

    public String updateStorageConfig(StorageConfig storageConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getStorageConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .storageConfig(storageConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return storageConfig.getVersion();
    }

    public String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getUserInterfaceConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .userInterfaceConfig(userInterfaceConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userInterfaceConfig.getVersion();
    }

    public String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getAdvancedConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .advancedConfig(advancedConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return advancedConfig.getVersion();
    }

    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.getPluginConfigs());
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.getId().equals(loopPluginConfig.getId())) {
                    if (!loopPluginConfig.getVersion().equals(priorVersion)) {
                        throw new OptimisticLockException();
                    }
                    i.set(pluginConfig);
                }
            }
            Config updatedConfig = Config.builder(config)
                    .pluginConfigs(ImmutableList.copyOf(pluginConfigs))
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyPluginConfigListeners(pluginConfig.getId());
        return pluginConfig.getVersion();
    }

    @ReadOnly
    public List<AdhocPointcutConfig> getAdhocPointcutConfigs() {
        return config.getAdhocPointcutConfigs();
    }

    public String insertAdhocPointcutConfig(AdhocPointcutConfig adhocPointcutConfig)
            throws IOException {
        synchronized (writeLock) {
            List<AdhocPointcutConfig> adhocPointcutConfigs =
                    Lists.newArrayList(config.getAdhocPointcutConfigs());
            adhocPointcutConfigs.add(adhocPointcutConfig);
            Config updatedConfig = Config.builder(config)
                    .adhocPointcutConfigs(ImmutableList.copyOf(adhocPointcutConfigs))
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        return adhocPointcutConfig.getVersion();
    }

    public String updateAdhocPointcutConfig(String priorVersion,
            AdhocPointcutConfig adhocPointcutConfig) throws IOException {
        synchronized (writeLock) {
            List<AdhocPointcutConfig> adhocPointcutConfigs =
                    Lists.newArrayList(config.getAdhocPointcutConfigs());
            boolean found = false;
            for (ListIterator<AdhocPointcutConfig> i = adhocPointcutConfigs
                    .listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().getVersion())) {
                    i.set(adhocPointcutConfig);
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("adhoc pointcut config unique hash '{}' not found", priorVersion);
                return priorVersion;
            }
            Config updatedConfig = Config.builder(config)
                    .adhocPointcutConfigs(ImmutableList.copyOf(adhocPointcutConfigs))
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        return adhocPointcutConfig.getVersion();
    }

    public void deleteAdhocPointcutConfig(String version) throws IOException {
        synchronized (writeLock) {
            List<AdhocPointcutConfig> adhocPointcutConfigs =
                    Lists.newArrayList(config.getAdhocPointcutConfigs());
            boolean found = false;
            for (ListIterator<AdhocPointcutConfig> i = adhocPointcutConfigs
                    .listIterator(); i.hasNext();) {
                if (version.equals(i.next().getVersion())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("adhoc pointcut config version '{}' not found", version);
                return;
            }
            Config updatedConfig = Config.builder(config)
                    .adhocPointcutConfigs(ImmutableList.copyOf(adhocPointcutConfigs))
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void notifyPluginConfigListeners(String pluginId) {
        for (ConfigListener configListener : pluginConfigListeners.get(pluginId)) {
            configListener.onChange();
        }
    }

    private void notifyAllPluginConfigListeners() {
        for (ConfigListener configListener : pluginConfigListeners.values()) {
            configListener.onChange();
        }
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        if (!configFile.delete()) {
            throw new IOException("Could not delete file: " + configFile.getCanonicalPath());
        }
        config = loadConfig(configFile, pluginDescriptorCache);
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    private static Config loadConfig(File configFile, PluginDescriptorCache pluginDescriptorCache)
            throws IOException {
        if (!configFile.exists()) {
            Config config = Config.getDefault(pluginDescriptorCache.getPluginDescriptors());
            ConfigMapper.writeValue(configFile, config);
            return config;
        }
        String content = Files.toString(configFile, Charsets.UTF_8);
        Config config;
        String warningMessage = null;
        try {
            config = new ConfigMapper(pluginDescriptorCache.getPluginDescriptors())
                    .readValue(content);
        } catch (JsonProcessingException e) {
            logger.warn("error in config file {}: {}", configFile.getAbsolutePath(),
                    e.getMessage());
            File backupFile = new File(configFile.getParentFile(), configFile.getName()
                    + ".invalid-orig");
            config = Config.getDefault(pluginDescriptorCache.getPluginDescriptors());
            try {
                Files.copy(configFile, backupFile);
                warningMessage = "due to an error in the config file, it has been backed up to"
                        + " extension '.invalid-orig' and overwritten with the default config";
            } catch (IOException f) {
                logger.warn("error making a copy of the invalid config file before overwriting it",
                        f);
                warningMessage = "due to an error in the config file, it has been overwritten with"
                        + " the default config";
            }
        }
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        writeToFileIfNeeded(config, configFile, content);
        if (warningMessage != null) {
            logger.warn(warningMessage);
        }
        return config;
    }

    private static void writeToFileIfNeeded(Config config, File configFile, String existingContent)
            throws IOException {
        String content = ConfigMapper.writeValueAsString(config);
        if (content.equals(existingContent)) {
            // it's nice to preserve the correct modification stamp on the file to track when it was
            // last really changed
            return;
        }
        Files.write(content, configFile, Charsets.UTF_8);
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}
}
