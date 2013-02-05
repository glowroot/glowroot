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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.core.util.OnlyUsedByTests;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import checkers.nullness.quals.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

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

    private final File configFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();

    private volatile Config config;

    @Inject
    ConfigService(@Named("data.dir") File dataDir) {
        logger.debug("<init>()");
        configFile = new File(dataDir, "config.json");
        try {
            config = Config.fromFile(configFile);
        } catch (IOException e) {
            logger.warn("error reading config.json file: " + e.getMessage());
            config = Config.getDefault();
            // no point in trying to save the invalid config file since it couldn't be read
        } catch (JsonSyntaxException e) {
            logger.warn("error loading config.json file: " + e.getMessage());
            config = Config.getDefault();
            saveInvalidConfigFile(configFile);
        }
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        config.writeToFileIfNeeded(configFile);
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

    // TODO pass around config version to avoid possible clobbering
    public void updateGeneralConfig(GeneralConfig generalConfig) {
        synchronized (writeLock) {
            Config updatedConfig = Config.builder(config)
                    .generalConfig(generalConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void updateCoarseProfilingConfig(CoarseProfilingConfig coarseProfilingConfig) {
        synchronized (writeLock) {
            Config updatedConfig = Config.builder(config)
                    .coarseProfilingConfig(coarseProfilingConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void updateFineProfilingConfig(FineProfilingConfig fineProfilingConfig) {
        synchronized (writeLock) {
            Config updatedConfig = Config.builder(config)
                    .fineProfilingConfig(fineProfilingConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void updateUserConfig(UserConfig userConfig) {
        synchronized (writeLock) {
            Config updatedConfig = Config.builder(config)
                    .userConfig(userConfig)
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void updatePluginConfig(PluginConfig pluginConfig) {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.getPluginConfigs());
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                if (pluginConfig.getId().equals(i.next().getId())) {
                    i.set(pluginConfig);
                }
            }
            Config updatedConfig = Config.builder(config)
                    .pluginConfigs(ImmutableList.copyOf(pluginConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    public ImmutableList<PointcutConfig> readPointcutConfigs() {
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
        return pointcutConfig.getUniqueHash();
    }

    public String updatePointcutConfig(String previousUniqueHash, PointcutConfig pointcutConfig) {
        synchronized (writeLock) {
            List<PointcutConfig> pointcutConfigs = Lists.newArrayList(config.getPointcutConfigs());
            boolean found = false;
            for (ListIterator<PointcutConfig> i = pointcutConfigs.listIterator(); i.hasNext();) {
                if (previousUniqueHash.equals(i.next().getUniqueHash())) {
                    i.set(pointcutConfig);
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("pointcut config unique hash '{}' not found", previousUniqueHash);
                return previousUniqueHash;
            }
            Config updatedConfig = Config.builder(config)
                    .pointcutConfigs(ImmutableList.copyOf(pointcutConfigs))
                    .build();
            updatedConfig.writeToFileIfNeeded(configFile);
            config = updatedConfig;
        }
        return pointcutConfig.getUniqueHash();
    }

    public void deletePointcutConfig(String uniqueHash) {
        synchronized (writeLock) {
            List<PointcutConfig> pointcutConfigs = Lists.newArrayList(config.getPointcutConfigs());
            boolean found = false;
            for (ListIterator<PointcutConfig> i = pointcutConfigs.listIterator(); i.hasNext();) {
                if (uniqueHash.equals(i.next().getUniqueHash())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("pointcut config unique hash '{}' not found", uniqueHash);
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

    @OnlyUsedByTests
    public void deleteConfig() throws IOException, JsonSyntaxException {
        configFile.delete();
        config = Config.fromFile(configFile);
    }

    // make a copy of the invalid config file since it will be overwritten with default config
    private static void saveInvalidConfigFile(File configFile) {
        try {
            File copy = new File(configFile.getParentFile(), configFile.getName() + ".invalid");
            Files.copy(configFile, copy);
        } catch (IOException f) {
        }
    }
}
