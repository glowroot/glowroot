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
import java.util.jar.JarFile;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.glowroot.weaving.WeavingClassFileTransformer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class ConfigModule {

    private final PluginDescriptorCache pluginDescriptorCache;
    private final ConfigService configService;

    public ConfigModule(@Nullable Instrumentation instrumentation, File dataDir,
            boolean viewerModeEnabled) throws IOException, URISyntaxException {
        // instrumentation is null when debugging with IsolatedWeavingClassLoader instead of
        // javaagent
        if (instrumentation != null) {
            addPluginJarsToClasspath(instrumentation);
        }
        if (viewerModeEnabled) {
            pluginDescriptorCache = PluginDescriptorCache.createInViewerMode();
        } else {
            pluginDescriptorCache = PluginDescriptorCache.create();
        }
        configService = new ConfigService(dataDir, pluginDescriptorCache);
    }

    public PluginDescriptorCache getPluginDescriptorCache() {
        return pluginDescriptorCache;
    }

    public ConfigService getConfigService() {
        return configService;
    }

    private static void addPluginJarsToClasspath(Instrumentation instrumentation)
            throws URISyntaxException, IOException {
        boolean useBootstrapClassLoader = WeavingClassFileTransformer.isInBootstrapClassLoader();
        for (File pluginJar : Plugins.getPluginJars()) {
            if (useBootstrapClassLoader) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
            } else {
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(pluginJar));
            }
        }
    }
}
