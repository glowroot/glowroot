/*
 * Copyright 2014-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.glowroot.markers.UsedByReflection;
import org.glowroot.plugin.api.PluginServices;

@UsedByReflection
public class PluginServicesRegistry {

    private static volatile @Nullable PluginServicesRegistry INSTANCE;

    private final LoadingCache<String, PluginServices> pluginServices;

    private final Supplier<PluginServices> pluginServicesWithoutPlugin;

    private PluginServicesRegistry(final PluginServicesFactory pluginServicesFactory) {
        pluginServices = CacheBuilder.newBuilder().build(new CacheLoader<String, PluginServices>() {
            @Override
            public PluginServices load(String pluginId) {
                return pluginServicesFactory.create(pluginId);
            }
        });
        pluginServicesWithoutPlugin = Suppliers.memoize(new Supplier<PluginServices>() {
            @Override
            public PluginServices get() {
                return pluginServicesFactory.create(null);
            }
        });
    }

    private PluginServices getPluginServices(@Nullable String pluginId) {
        if (pluginId == null) {
            return pluginServicesWithoutPlugin.get();
        }
        return pluginServices.getUnchecked(pluginId);
    }

    // called via reflection from org.glowroot.plugin.api.PluginServices
    // also called via reflection from generated pointcut config advice
    //
    // null return value indicates glowroot hasn't started yet
    @UsedByReflection
    public static @Nullable PluginServices get(@Nullable String pluginId) {
        PluginServicesRegistry instanceLocal = INSTANCE;
        if (instanceLocal == null) {
            return null;
        }
        return instanceLocal.getPluginServices(pluginId);
    }

    static void initStaticState(PluginServicesFactory pluginServicesFactory) {
        INSTANCE = new PluginServicesRegistry(pluginServicesFactory);
    }

    static void clearStaticState() {
        INSTANCE = null;
    }

    interface PluginServicesFactory {
        PluginServices create(@Nullable String pluginId);
    }
}
