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
package org.glowroot.config;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Value.Immutable
abstract class PluginCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginCache.class);

    abstract List<File> pluginJars();
    abstract List<PluginDescriptor> pluginDescriptors();

    static PluginCache create(@Nullable File glowrootJarFile, boolean viewerModeEnabled)
            throws URISyntaxException, IOException {
        ImmutablePluginCache.Builder builder = ImmutablePluginCache.builder();
        List<URL> descriptorURLs = Lists.newArrayList();
        if (glowrootJarFile != null) {
            List<File> pluginJars = getPluginJars(glowrootJarFile);
            builder.addAllPluginJars(pluginJars);
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
        if (viewerModeEnabled) {
            builder.addAllPluginDescriptors(createInViewerMode(descriptorURLs));
        } else {
            builder.addAllPluginDescriptors(readPluginDescriptors(descriptorURLs));
        }
        return builder.build();
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

    private static @Nullable File getPluginsDir(File glowrootJarFile) throws IOException,
            URISyntaxException {
        File pluginsDir = new File(glowrootJarFile.getParentFile(), "plugins");
        if (!pluginsDir.exists()) {
            // it is ok to run without any plugins
            return null;
        }
        return pluginsDir;
    }

    private static ImmutableList<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = PluginCache.class.getClassLoader();
        if (loader == null) {
            return ImmutableList.copyOf(Iterators.forEnumeration(
                    ClassLoader.getSystemResources(resourceName)));
        }
        return ImmutableList.copyOf(Iterators.forEnumeration(loader.getResources(resourceName)));
    }

    private static List<PluginDescriptor> createInViewerMode(List<URL> descriptorURLs)
            throws IOException, URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(descriptorURLs);
        List<PluginDescriptor> pluginDescriptorsWithoutAdvice = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(pluginDescriptor.copyWithoutAdvice());
        }
        return sorted(pluginDescriptorsWithoutAdvice);
    }

    private static List<PluginDescriptor> readPluginDescriptors(List<URL> descriptorURLs)
            throws IOException, URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (URL url : descriptorURLs) {
            try {
                String content = Resources.toString(url, Charsets.UTF_8);
                PluginDescriptor pluginDescriptor =
                        Marshaling.fromJson(content, PluginDescriptor.class);
                pluginDescriptors.add(pluginDescriptor);
            } catch (JsonProcessingException e) {
                logger.error("error parsing plugin descriptor: {}", url.toExternalForm(), e);
            }
        }
        return sorted(pluginDescriptors);
    }

    // sorted for display to console during startup and for plugin config sidebar menu
    private static List<PluginDescriptor> sorted(List<PluginDescriptor> pluginDescriptors) {
        return PluginDescriptor.specialOrderingByName.immutableSortedCopy(pluginDescriptors);
    }
}
