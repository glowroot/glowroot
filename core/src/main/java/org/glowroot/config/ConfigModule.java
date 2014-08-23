/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.config;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class ConfigModule {

    private final PluginCache pluginCache;
    private final PluginDescriptorCache pluginDescriptorCache;
    private final ConfigService configService;

    public ConfigModule(File dataDir, @Nullable Instrumentation instrumentation,
            @Nullable File glowrootJarFile, boolean viewerModeEnabled) throws IOException,
            URISyntaxException {
        pluginCache = new PluginCache(glowrootJarFile);
        if (viewerModeEnabled) {
            pluginDescriptorCache = PluginDescriptorCache.createInViewerMode(pluginCache);
        } else {
            pluginDescriptorCache = PluginDescriptorCache.create(pluginCache, instrumentation,
                    dataDir);
        }
        configService = new ConfigService(dataDir, pluginDescriptorCache);
    }

    public PluginDescriptorCache getPluginDescriptorCache() {
        return pluginDescriptorCache;
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public ImmutableList<File> getPluginJars() {
        return pluginCache.getPluginJars();
    }
}
