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
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PluginCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginCache.class);

    private final ImmutableList<File> pluginJars;
    private final ImmutableList<URL> descriptorURLs;

    PluginCache(@Nullable File glowrootJarFile) throws URISyntaxException, IOException {
        List<URL> descriptorURLs = Lists.newArrayList();
        if (glowrootJarFile == null) {
            pluginJars = ImmutableList.of();
        } else {
            pluginJars = getPluginJars(glowrootJarFile);
            for (File pluginJar : pluginJars) {
                descriptorURLs.add(new URL("jar:" + pluginJar.toURI()
                        + "!/META-INF/glowroot.plugin.json"));
            }
            for (File file : getStandaloneDescriptors(glowrootJarFile)) {
                descriptorURLs.add(file.toURI().toURL());
            }
        }
        // also add descriptors on the class path (this is primarily for integration tests)
        descriptorURLs.addAll(getResources("META-INF/glowroot.plugin.json"));
        this.descriptorURLs = ImmutableList.copyOf(descriptorURLs);
    }

    ImmutableList<File> getPluginJars() {
        return pluginJars;
    }

    ImmutableList<URL> getDescriptorURLs() {
        return descriptorURLs;
    }

    private static ImmutableList<File> getPluginJars(File glowrootJarFile)
            throws URISyntaxException, IOException {
        File pluginsDir = getPluginsDir(glowrootJarFile);
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

    private static ImmutableList<File> getStandaloneDescriptors(File glowrootJarFile)
            throws IOException, URISyntaxException {
        File pluginsDir = getPluginsDir(glowrootJarFile);
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
    private static File getPluginsDir(File glowrootJarFile) throws IOException,
            URISyntaxException {
        File pluginsDir = new File(glowrootJarFile.getParentFile(), "plugins");
        if (!pluginsDir.exists()) {
            // it is ok to run without any plugins
            return null;
        }
        return pluginsDir;
    }

    private static ImmutableList<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = PluginDescriptorCache.class.getClassLoader();
        if (loader == null) {
            return ImmutableList.copyOf(Iterators.forEnumeration(
                    ClassLoader.getSystemResources(resourceName)));
        }
        return ImmutableList.copyOf(Iterators.forEnumeration(loader.getResources(resourceName)));
    }
}
