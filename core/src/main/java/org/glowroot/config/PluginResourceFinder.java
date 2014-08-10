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
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginResourceFinder {

    private static final Logger logger = LoggerFactory.getLogger(PluginResourceFinder.class);

    private final ImmutableList<File> pluginJars;
    private final ImmutableList<URL> descriptorURLs;

    public PluginResourceFinder(File glowrootJarFile) throws URISyntaxException, IOException {
        pluginJars = getPluginJars(glowrootJarFile);
        List<URL> descriptorURLs = Lists.newArrayList();
        for (File pluginJar : pluginJars) {
            descriptorURLs.add(new URL("jar:" + pluginJar.toURI()
                    + "!/META-INF/glowroot.plugin.json"));
        }
        for (File file : getStandaloneDescriptors(glowrootJarFile)) {
            descriptorURLs.add(file.toURI().toURL());
        }
        this.descriptorURLs = ImmutableList.copyOf(descriptorURLs);
    }

    public ImmutableList<File> getPluginJars() {
        return pluginJars;
    }

    @Nullable
    URL findResource(String name) {
        for (File pluginJar : pluginJars) {
            try {
                URL url = new URL("jar:" + pluginJar.toURI() + "!/" + name);
                // call openStream() to test if this exists
                url.openStream();
                return url;
            } catch (IOException e) {
                logger.debug(e.getMessage(), e);
            }
        }
        return null;
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
}
