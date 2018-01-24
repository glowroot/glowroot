/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.impl;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.internal.PluginService;

public class PluginServiceImpl implements PluginService {

    private final TimerNameCache timerNameCache;

    private final LoadingCache<String, ConfigService> configServices;

    public PluginServiceImpl(TimerNameCache timerNameCache,
            final ConfigServiceFactory configServiceFactory) {
        this.timerNameCache = timerNameCache;
        configServices = CacheBuilder.newBuilder()
                .build(new CacheLoader<String, ConfigService>() {
                    @Override
                    public ConfigService load(String pluginId) {
                        return configServiceFactory.create(pluginId);
                    }
                });
    }

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return timerNameCache.getTimerName(adviceClass);
    }

    @Override
    public TimerName getTimerName(String name) {
        return timerNameCache.getTimerName(name);
    }

    @Override
    public ConfigService getConfigService(String pluginId) {
        return configServices.getUnchecked(pluginId);
    }

    public interface ConfigServiceFactory {
        ConfigService create(String pluginId);
    }
}
