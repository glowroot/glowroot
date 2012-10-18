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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.PluginServices.ConfigListener;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();

    private volatile CoreConfig coreConfig;
    private volatile CoarseProfilingConfig coarseProfilingConfig;
    private volatile FineProfilingConfig fineProfilingConfig;
    private volatile UserTracingConfig userTracingConfig;
    private final ConcurrentMap<String, PluginConfig> pluginConfigs;

    @Inject
    ConfigService(ConfigDao configDao) {
        logger.debug("<init>()");
        this.configDao = configDao;

        CoreConfig coreConfig = configDao.readCoreConfig();
        if (coreConfig == null) {
            logger.debug("<init>(): default core config is being used");
            this.coreConfig = CoreConfig.getDefaultInstance();
        } else {
            logger.debug("<init>(): core config was read from local data store: {}", coreConfig);
            this.coreConfig = coreConfig;
        }

        CoarseProfilingConfig coarseProfilingConfig = configDao.readCoarseProfilingConfig();
        if (coarseProfilingConfig == null) {
            logger.debug("<init>(): default coarse profiling config is being used");
            this.coarseProfilingConfig = CoarseProfilingConfig.getDefaultInstance();
        } else {
            logger.debug("<init>(): coarse profiling config was read from local data store: {}",
                    coarseProfilingConfig);
            this.coarseProfilingConfig = coarseProfilingConfig;
        }

        FineProfilingConfig fineProfilingConfig = configDao.readFineProfilingConfig();
        if (fineProfilingConfig == null) {
            logger.debug("<init>(): default fine profiling config is being used");
            this.fineProfilingConfig = FineProfilingConfig.getDefaultInstance();
        } else {
            logger.debug("<init>(): fine profiling config was read from local data store: {}",
                    fineProfilingConfig);
            this.fineProfilingConfig = fineProfilingConfig;
        }

        UserTracingConfig userTracingConfig = configDao.readUserTracingConfig();
        if (userTracingConfig == null) {
            logger.debug("<init>(): default user tracing config is being used");
            this.userTracingConfig = UserTracingConfig.getDefaultInstance();
        } else {
            logger.debug("<init>(): user tracing config was read from local data store: {}",
                    userTracingConfig);
            this.userTracingConfig = userTracingConfig;
        }

        pluginConfigs = Maps.newConcurrentMap();
        Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(
                Plugins.getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors());
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig pluginConfig = configDao.readPluginConfig(pluginDescriptor.getId());
            if (pluginConfig != null) {
                pluginConfigs.put(pluginDescriptor.getId(), pluginConfig);
            } else {
                pluginConfigs.put(pluginDescriptor.getId(),
                        PluginConfig.builder(pluginDescriptor.getId()).build());
            }
        }
    }

    public CoreConfig getCoreConfig() {
        return coreConfig;
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        return coarseProfilingConfig;
    }

    public FineProfilingConfig getFineProfilingConfig() {
        return fineProfilingConfig;
    }

    public UserTracingConfig getUserTracingConfig() {
        return userTracingConfig;
    }

    public PluginConfig getPluginConfig(String pluginId) {
        PluginConfig pluginConfig = pluginConfigs.get(pluginId);
        if (pluginConfig == null) {
            logger.error("unexpected plugin id '{}', available plugin ids: {}", pluginId, Joiner
                    .on(", ").join(pluginConfigs.keySet()));
            return PluginConfig.getNopInstance();
        } else {
            return pluginConfig;
        }
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    // TODO pass around config version to avoid possible clobbering
    public void storeCoreConfig(CoreConfig config) {
        configDao.storeCoreConfig(config);
        // re-read from dao just to fail quickly in case of an issue
        this.coreConfig = configDao.readCoreConfig();
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void storeCoarseProfilingConfig(CoarseProfilingConfig config) {
        configDao.storeCoarseProfilingConfig(config);
        // re-read from dao just to fail quickly in case of an issue
        this.coarseProfilingConfig = configDao.readCoarseProfilingConfig();
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void storeFineProfilingConfig(FineProfilingConfig config) {
        configDao.storeFineProfilingConfig(config);
        // re-read from dao just to fail quickly in case of an issue
        this.fineProfilingConfig = configDao.readFineProfilingConfig();
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void storeUserTracingConfig(UserTracingConfig config) {
        configDao.storeUserTracingConfig(config);
        // re-read from dao just to fail quickly in case of an issue
        this.userTracingConfig = configDao.readUserTracingConfig();
        notifyConfigListeners();
    }

    // TODO pass around config version to avoid possible clobbering
    public void storePluginConfig(String pluginId, PluginConfig pluginConfig) {
        configDao.storePluginConfig(pluginId, pluginConfig);
        // re-read from dao just to fail quickly in case of an issue
        pluginConfigs.put(pluginId, configDao.readPluginConfig(pluginId));
        notifyConfigListeners();
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
}
