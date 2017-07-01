/*
 * Copyright 2014-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.api.internal.GlowrootService;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.internal.ServiceRegistry;

public class ServiceRegistryImpl implements ServiceRegistry {

    private static volatile @MonotonicNonNull ServiceRegistryImpl instance;

    private final GlowrootService glowrootService;
    private final TimerNameCache timerNameCache;

    private final LoadingCache<String, ConfigService> configServices;

    private ServiceRegistryImpl(GlowrootService glowrootService, TimerNameCache timerNameCache,
            final ConfigServiceFactory configServiceFactory) {
        this.glowrootService = glowrootService;
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

    // called via reflection from org.glowroot.agent.plugin.api.Agent
    // also called via reflection from generated pointcut config advice
    public static @Nullable ServiceRegistry getInstance() {
        return instance;
    }

    // called via reflection from org.glowroot.agent.api.Glowroot
    // also called via reflection from generated pointcut config advice
    public static @Nullable GlowrootService getGlowrootService() {
        return instance == null ? null : instance.glowrootService;
    }

    public static void init(GlowrootService glowrootService, TimerNameCache timerNameCache,
            ConfigServiceFactory configServiceFactory) {
        instance = new ServiceRegistryImpl(glowrootService, timerNameCache, configServiceFactory);
    }

    public interface ConfigServiceFactory {
        ConfigService create(String pluginId);
    }
}
