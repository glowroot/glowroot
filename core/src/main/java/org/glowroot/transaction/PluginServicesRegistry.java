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
import org.glowroot.plugin.api.config.ConfigService;
import org.glowroot.plugin.api.transaction.TransactionService;

@UsedByReflection
public class PluginServicesRegistry {

    private static volatile @Nullable PluginServicesRegistry INSTANCE;

    private final LoadingCache<String, ConfigService> configServices;

    private final Supplier<TransactionService> transactionService;

    private PluginServicesRegistry(final PluginServicesFactory pluginServicesFactory) {
        configServices = CacheBuilder.newBuilder().build(new CacheLoader<String, ConfigService>() {
            @Override
            public ConfigService load(String pluginId) {
                return pluginServicesFactory.createConfigService(pluginId);
            }
        });
        transactionService = Suppliers.memoize(new Supplier<TransactionService>() {
            @Override
            public TransactionService get() {
                return pluginServicesFactory.createTransactionService();
            }
        });
    }

    // called via reflection from org.glowroot.plugin.api.Plugin
    // also called via reflection from generated pointcut config advice
    @UsedByReflection
    public static TransactionService getTransactionService() {
        PluginServicesRegistry instanceLocal = INSTANCE;
        if (instanceLocal == null) {
            throw new IllegalStateException("Glowroot has not started");
        }
        return instanceLocal.transactionService.get();
    }

    // called via reflection from org.glowroot.plugin.api.Plugin
    // also called via reflection from generated pointcut config advice
    @UsedByReflection
    public static ConfigService getConfigService(String pluginId) {
        PluginServicesRegistry instanceLocal = INSTANCE;
        if (instanceLocal == null) {
            throw new IllegalStateException("Glowroot has not started");
        }
        return instanceLocal.configServices.getUnchecked(pluginId);
    }

    static void initStaticState(PluginServicesFactory pluginServicesFactory) {
        INSTANCE = new PluginServicesRegistry(pluginServicesFactory);
    }

    static void clearStaticState() {
        INSTANCE = null;
    }

    interface PluginServicesFactory {
        TransactionService createTransactionService();
        ConfigService createConfigService(String pluginId);
    }
}
