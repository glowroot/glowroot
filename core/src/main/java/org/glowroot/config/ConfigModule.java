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
package org.glowroot.config;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.jar.JarFile;

import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class ConfigModule {

    private static final Logger logger = LoggerFactory.getLogger(ConfigModule.class);

    private final PluginDescriptorCache pluginDescriptorCache;
    private final ConfigService configService;

    public ConfigModule(@Nullable Instrumentation instrumentation, File dataDir)
            throws IOException, URISyntaxException {
        // instrumentation is null when debugging with IsolatedWeavingClassLoader instead of
        // javaagent
        if (instrumentation != null) {
            addPluginJarsToSystemClasspath(instrumentation);
        }
        pluginDescriptorCache = new PluginDescriptorCache();
        configService = new ConfigService(dataDir, pluginDescriptorCache);
    }

    public PluginDescriptorCache getPluginDescriptorCache() {
        return pluginDescriptorCache;
    }

    public ConfigService getConfigService() {
        return configService;
    }

    private static void addPluginJarsToSystemClasspath(Instrumentation instrumentation)
            throws URISyntaxException, IOException {
        URL agentJarLocation =
                ConfigModule.class.getProtectionDomain().getCodeSource().getLocation();
        if (agentJarLocation == null) {
            throw new IOException("Could not determine glowroot jar location");
        }
        File agentJarFile = new File(agentJarLocation.toURI());
        if (!agentJarFile.getName().endsWith(".jar")) {
            if (isRunningDelegatingJavaagent()) {
                // this is ok, running tests under delegating javaagent
                return;
            }
            throw new IOException("Could not determine glowroot jar location");
        }
        File pluginsDir = new File(agentJarFile.getParentFile(), "plugins");
        if (!pluginsDir.exists()) {
            // it is ok to run without any plugins
            return;
        }
        File[] pluginJars = pluginsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        if (pluginJars == null) {
            logger.warn("listFiles() returned null on directory: {}", pluginsDir.getAbsolutePath());
            return;
        }
        for (File pluginJar : pluginJars) {
            instrumentation.appendToSystemClassLoaderSearch(new JarFile(pluginJar));
        }
    }

    private static boolean isRunningDelegatingJavaagent() {
        try {
            Class.forName("org.glowroot.container.javaagent.DelegatingJavaagent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
