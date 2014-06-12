/*
 * Copyright 2014 the original author or authors.
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
import java.net.URISyntaxException;
import java.net.URL;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Plugins {

    private static final Logger logger = LoggerFactory.getLogger(Plugins.class);

    private Plugins() {}

    static ImmutableList<File> getPluginJars() throws URISyntaxException, IOException {
        File pluginsDir = getPluginsDir();
        if (pluginsDir == null) {
            return ImmutableList.of();
        }
        File[] pluginJars = pluginsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        if (pluginJars == null) {
            logger.warn("listFiles() returned null on directory: {}", pluginsDir.getAbsolutePath());
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(pluginJars);
    }

    static ImmutableList<File> getStandalonePluginDescriptorFiles() throws IOException,
            URISyntaxException {
        File pluginsDir = getPluginsDir();
        if (pluginsDir == null) {
            return ImmutableList.of();
        }
        File[] pluginDescriptorFiles = pluginsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".json");
            }
        });
        if (pluginDescriptorFiles == null) {
            logger.warn("listFiles() returned null on directory: {}", pluginsDir.getAbsolutePath());
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(pluginDescriptorFiles);
    }

    @Nullable
    private static File getPluginsDir() throws IOException, URISyntaxException {
        URL agentJarLocation = Plugins.class.getProtectionDomain().getCodeSource().getLocation();
        if (agentJarLocation == null) {
            throw new IOException("Could not determine glowroot jar location");
        }
        File agentJarFile = new File(agentJarLocation.toURI());
        if (!agentJarFile.getName().endsWith(".jar")) {
            if (isRunningDelegatingJavaagent()) {
                // this is ok, running tests under delegating javaagent
                return null;
            }
            throw new IOException("Could not determine glowroot jar location");
        }
        File pluginsDir = new File(agentJarFile.getParentFile(), "plugins");
        if (!pluginsDir.exists()) {
            // it is ok to run without any plugins
            return null;
        }
        return pluginsDir;
    }

    private static boolean isRunningDelegatingJavaagent() {
        try {
            // TODO check javaagent arg instead of just checking if it is somewhere on the classpath
            Class.forName("org.glowroot.container.javaagent.DelegatingJavaagent");
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return false;
        }
    }
}
