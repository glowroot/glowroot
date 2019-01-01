/*
 * Copyright 2014-2019 the original author or authors.
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
package org.glowroot.agent.config;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.util.ObjectMappers;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
public abstract class PluginCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginCache.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    static {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(InstrumentationConfig.class,
                ImmutableInstrumentationConfig.class);
        module.addAbstractTypeMapping(PropertyDescriptor.class, ImmutablePropertyDescriptor.class);
        mapper.registerModule(module);
    }

    public abstract ImmutableList<File> pluginJars();
    public abstract ImmutableList<PluginDescriptor> pluginDescriptors();

    public static PluginCache create(@Nullable File pluginsDir, boolean offlineViewer)
            throws Exception {
        ImmutablePluginCache.Builder builder = ImmutablePluginCache.builder();
        List<File> pluginJars = getPluginJars(pluginsDir);
        builder.addAllPluginJars(pluginJars);
        List<PluginDescriptor> unsortedPluginDescriptors = Lists.newArrayList();
        if (offlineViewer) {
            unsortedPluginDescriptors.addAll(createForOfflineViewer(pluginJars, pluginsDir));
        } else {
            unsortedPluginDescriptors.addAll(readPluginDescriptors(pluginJars, pluginsDir));
        }
        // when using uber jar, get the (aggregated) plugin list
        URL plugins = PluginCache.class.getResource("/META-INF/glowroot.plugins.json");
        if (plugins != null) {
            List<PluginDescriptor> pluginDescriptors = mapper.readValue(plugins,
                    new TypeReference<List<ImmutablePluginDescriptor>>() {});
            checkNotNull(pluginDescriptors);
            unsortedPluginDescriptors.addAll(pluginDescriptors);
        }
        return builder
                .addAllPluginDescriptors(
                        new PluginDescriptorOrdering().sortedCopy(unsortedPluginDescriptors))
                .build();
    }

    private static ImmutableList<File> getPluginJars(@Nullable File pluginsDir) {
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

    private static ImmutableList<File> getStandaloneDescriptors(@Nullable File pluginsDir) {
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

    private static ImmutableList<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = PluginCache.class.getClassLoader();
        if (loader == null) {
            return ImmutableList
                    .copyOf(Iterators.forEnumeration(ClassLoader.getSystemResources(resourceName)));
        } else {
            return ImmutableList
                    .copyOf(Iterators.forEnumeration(loader.getResources(resourceName)));
        }
    }

    private static List<PluginDescriptor> createForOfflineViewer(List<File> pluginJars,
            @Nullable File pluginsDir) throws IOException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(pluginJars, pluginsDir);
        List<PluginDescriptor> pluginDescriptorsWithoutAdvice = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(ImmutablePluginDescriptor.builder()
                    .copyFrom(pluginDescriptor)
                    .aspects(ImmutableList.<String>of())
                    .build());
        }
        return pluginDescriptorsWithoutAdvice;
    }

    private static List<PluginDescriptor> readPluginDescriptors(List<File> pluginJars,
            @Nullable File pluginsDir) throws IOException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (File pluginJar : pluginJars) {
            URL url = new URL("jar:" + pluginJar.toURI() + "!/META-INF/glowroot.plugin.json");
            buildPluginDescriptor(url, pluginJar, pluginDescriptors);
        }
        for (File file : getStandaloneDescriptors(pluginsDir)) {
            buildPluginDescriptor(file.toURI().toURL(), null, pluginDescriptors);
        }
        // also add descriptors on the class path (this is primarily for integration tests)
        for (URL url : getResources("META-INF/glowroot.plugin.json")) {
            buildPluginDescriptor(url, null, pluginDescriptors);
        }
        return pluginDescriptors;
    }

    private static void buildPluginDescriptor(URL url, @Nullable File pluginJar,
            List<PluginDescriptor> pluginDescriptors) throws IOException {
        String content = Resources.toString(url, UTF_8);
        ImmutablePluginDescriptor pluginDescriptor;
        try {
            pluginDescriptor = mapper.readValue(content, ImmutablePluginDescriptor.class);
        } catch (JsonProcessingException e) {
            logger.error("error parsing plugin descriptor: {}", url.toExternalForm(), e);
            return;
        }
        pluginDescriptors.add(pluginDescriptor.withPluginJar(pluginJar));
    }

    private static class PluginDescriptorOrdering extends Ordering<PluginDescriptor> {
        @Override
        public int compare(PluginDescriptor left, PluginDescriptor right) {
            return left.id().compareToIgnoreCase(right.id());
        }
    }
}
