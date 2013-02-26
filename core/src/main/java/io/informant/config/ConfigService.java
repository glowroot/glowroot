/**
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

import io.informant.api.PluginServices.ConfigListener;
import io.informant.util.OnlyUsedByTests;
import io.informant.util.Singleton;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.gson.JsonSyntaxException;

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

    private final PluginInfoCache pluginInfoCache;
    private final File configFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener> create());

    private volatile Config config;

    ConfigService(File dataDir, PluginInfoCache pluginInfoCache) {
        logger.debug("<init>()");
        this.pluginInfoCache = pluginInfoCache;
        configFile = new File(dataDir, "config.json");
        config = loadConfig(configFile, pluginInfoCache);
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

    public UserConfig getUserConfig() {
        return config.getUserConfig();
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

    public String updateGeneralConfig(GeneralConfig generalConfig, String priorVersionHash)
            throws OptimisticLockException {
        boolean notifyPluginConfigListeners;
        synchronized (writeLock) {
            if (!config.getGeneralConfig().getVersionHash().equals(priorVersionHash)) {
                throw new OptimisticLockException();
            }
            boolean previousEnabled = config.getGeneralConfig().isEnabled();
            Config updatedConfig = Config.builder(config)
                    .generalConfig(generalConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
            notifyPluginConfigListeners = config.getGeneralConfig().isEnabled() != previousEnabled;
        }
        notifyConfigListeners();
        if (notifyPluginConfigListeners) {
            notifyAllPluginConfigListeners();
        }
        return generalConfig.getVersionHash();
    }

    public String updateCoarseProfilingConfig(CoarseProfilingConfig coarseProfilingConfig,
            String priorVersionHash) throws OptimisticLockException {
        synchronized (writeLock) {
            if (!config.getCoarseProfilingConfig().getVersionHash().equals(priorVersionHash)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .coarseProfilingConfig(coarseProfilingConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return coarseProfilingConfig.getVersionHash();
    }

    public String updateFineProfilingConfig(FineProfilingConfig fineProfilingConfig,
            String priorVersionHash) throws OptimisticLockException {
        synchronized (writeLock) {
            if (!config.getFineProfilingConfig().getVersionHash().equals(priorVersionHash)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .fineProfilingConfig(fineProfilingConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return fineProfilingConfig.getVersionHash();
    }

    public String updateUserConfig(UserConfig userConfig, String priorVersionHash)
            throws OptimisticLockException {
        synchronized (writeLock) {
            if (!config.getUserConfig().getVersionHash().equals(priorVersionHash)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .userConfig(userConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userConfig.getVersionHash();
    }

    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersionHash)
            throws OptimisticLockException {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.getPluginConfigs());
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.getId().equals(loopPluginConfig.getId())) {
                    if (!loopPluginConfig.getVersionHash().equals(priorVersionHash)) {
                        throw new OptimisticLockException();
                    }
                    i.set(pluginConfig);
                }
            }
            Config updatedConfig = Config.builder(config)
                    .pluginConfigs(ImmutableList.copyOf(pluginConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyPluginConfigListeners(pluginConfig.getId());
        return pluginConfig.getVersionHash();
    }

    @ReadOnly
    public List<PointcutConfig> getPointcutConfigs() {
        return config.getPointcutConfigs();
    }

    public String insertPointcutConfig(PointcutConfig pointcutConfig) {
        synchronized (writeLock) {
            List<PointcutConfig> pointcutConfigs = Lists.newArrayList(config.getPointcutConfigs());
            pointcutConfigs.add(pointcutConfig);
            Config updatedConfig = Config.builder(config)
                    .pointcutConfigs(ImmutableList.copyOf(pointcutConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        return pointcutConfig.getVersionHash();
    }

    public String updatePointcutConfig(String priorVersionHash, PointcutConfig pointcutConfig) {
        synchronized (writeLock) {
            List<PointcutConfig> pointcutConfigs = Lists.newArrayList(config.getPointcutConfigs());
            boolean found = false;
            for (ListIterator<PointcutConfig> i = pointcutConfigs.listIterator(); i.hasNext();) {
                if (priorVersionHash.equals(i.next().getVersionHash())) {
                    i.set(pointcutConfig);
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("pointcut config unique hash '{}' not found", priorVersionHash);
                return priorVersionHash;
            }
            Config updatedConfig = Config.builder(config)
                    .pointcutConfigs(ImmutableList.copyOf(pointcutConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        return pointcutConfig.getVersionHash();
    }

    public void deletePointcutConfig(String versionHash) {
        synchronized (writeLock) {
            List<PointcutConfig> pointcutConfigs = Lists.newArrayList(config.getPointcutConfigs());
            boolean found = false;
            for (ListIterator<PointcutConfig> i = pointcutConfigs.listIterator(); i.hasNext();) {
                if (versionHash.equals(i.next().getVersionHash())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("pointcut config unique hash '{}' not found", versionHash);
                return;
            }
            Config updatedConfig = Config.builder(config)
                    .pointcutConfigs(ImmutableList.copyOf(pointcutConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
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

    private static Config loadConfig(File configFile, PluginInfoCache pluginInfoCache) {
        Config config;
        if (configFile.exists()) {
            try {
                config = Config.fromFile(configFile, pluginInfoCache.getPluginInfos());
            } catch (IOException e) {
                logger.warn("error reading config.json file: " + e.getMessage());
                config = Config.getDefault(pluginInfoCache.getPluginInfos());
                // no point in trying to save the invalid config file since it couldn't be read
            } catch (JsonSyntaxException e) {
                logger.warn("error loading config.json file: " + e.getMessage());
                config = Config.getDefault(pluginInfoCache.getPluginInfos());
                backupInvalidConfigFile(configFile);
            }
        } else {
            config = Config.getDefault(pluginInfoCache.getPluginInfos());
        }
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        config.writeToFileIfNeeded(configFile);
        return config;
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        if (!configFile.delete()) {
            throw new IOException("Could not delete file: " + configFile.getCanonicalPath());
        }
        config = loadConfig(configFile, pluginInfoCache);
    }

    // make a copy of the invalid config file since it will be overwritten with default config
    private static void backupInvalidConfigFile(File configFile) {
        try {
            File copy = new File(configFile.getParentFile(), configFile.getName() + ".invalid");
            Files.copy(configFile, copy);
        } catch (IOException f) {
        }
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}
}
