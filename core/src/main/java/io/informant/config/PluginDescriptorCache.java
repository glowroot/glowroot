/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.config;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.Pointcut;
import io.informant.common.ObjectMappers;
import io.informant.weaving.Advice;
import io.informant.weaving.Advice.AdviceConstructionException;
import io.informant.weaving.MixinType;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginDescriptorCache {

    @ReadOnly
    private static final Logger logger = LoggerFactory.getLogger(PluginDescriptorCache.class);
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;

    public PluginDescriptorCache() {
        ImmutableList.Builder<PluginDescriptor> thePluginDescriptors = ImmutableList.builder();
        try {
            thePluginDescriptors.addAll(readPackagedPlugins());
            thePluginDescriptors.addAll(readInstalledPlugins());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        this.pluginDescriptors = thePluginDescriptors.build();

        ImmutableList.Builder<MixinType> theMixinTypes = ImmutableList.builder();
        ImmutableList.Builder<Advice> theAdvisors = ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : this.pluginDescriptors) {
            for (String aspect : pluginDescriptor.getAspects()) {
                try {
                    // don't initialize the aspect since that will trigger static initializers which
                    // will probably call PluginServices.get()
                    Class<?> aspectClass = Class.forName(aspect, false,
                            PluginDescriptor.class.getClassLoader());
                    theAdvisors.addAll(getAdvisors(aspectClass));
                    theMixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect);
                }
            }
        }
        this.mixinTypes = theMixinTypes.build();
        this.advisors = theAdvisors.build();
    }

    // don't return ImmutableList since this method is used by UiTestingMain and when UiTestingMain
    // is compiled by maven, it is compiled against shaded informant, but then if it is run inside
    // an IDE without rebuilding UiTestingMain it will fail since informant is then unshaded
    @Immutable
    public List<PluginDescriptor> getPluginDescriptors() {
        return pluginDescriptors;
    }

    @Nullable
    public PluginDescriptor getPluginDescriptor(String pluginId) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            if (pluginDescriptor.getId().equals(pluginId)) {
                return pluginDescriptor;
            }
        }
        return null;
    }

    // don't return ImmutableList since this method is used by SameJvmExecutionAdapter and when
    // SameJvmExecutionAdapter is compiled by maven, it is compiled against shaded informant,
    // but then if a unit test is run inside an IDE without rebuilding SameJvmExecutionAdapter it
    // will fail since informant is then unshaded
    @Immutable
    public List<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    // don't return ImmutableList, see comment above
    @Immutable
    public List<Advice> getAdvisors() {
        return advisors;
    }

    private static List<PluginDescriptor> readInstalledPlugins() throws IOException {
        List<PluginDescriptor> plugins = Lists.newArrayList();
        List<URL> urls = getResources("META-INF/io.informant.plugin.json");
        for (URL url : urls) {
            try {
                String content = Resources.toString(url, Charsets.UTF_8);
                PluginDescriptor pluginDescriptor =
                        ObjectMappers.readRequiredValue(mapper, content, PluginDescriptor.class);
                plugins.add(pluginDescriptor);
            } catch (JsonProcessingException e) {
                // no need to log stack trace
                logger.error("error in plugin descriptor {}: {}", url.toExternalForm(),
                        e.getMessage());
            }
        }
        return plugins;
    }

    @ReadOnly
    private static List<PluginDescriptor> readPackagedPlugins() throws IOException {
        URL url = getResource("META-INF/io.informant.package.json");
        if (url == null) {
            return ImmutableList.of();
        }
        String content = Resources.toString(url, Charsets.UTF_8);
        try {
            PackageDescriptor packageDescriptor =
                    ObjectMappers.readRequiredValue(mapper, content, PackageDescriptor.class);
            return packageDescriptor.getPlugins();
        } catch (JsonProcessingException e) {
            // no need to log stack trace
            logger.error("error in file {}: {}", url.toExternalForm(), e.getMessage());
            return ImmutableList.of();
        }
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Pointcut pointcut = memberClass.getAnnotation(Pointcut.class);
            if (pointcut != null) {
                try {
                    advisors.add(Advice.from(pointcut, memberClass, false));
                } catch (AdviceConstructionException e) {
                    logger.error("invalid advice '{}': {}", memberClass.getName(), e.getMessage());
                }
            }
        }
        return advisors;
    }

    private static List<MixinType> getMixinTypes(Class<?> aspectClass) {
        List<MixinType> mixinTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Mixin mixin = memberClass.getAnnotation(Mixin.class);
            if (mixin != null) {
                mixinTypes.add(MixinType.from(mixin, memberClass));
            }
        }
        return mixinTypes;
    }

    private static List<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = PluginDescriptorCache.class.getClassLoader();
        if (loader == null) {
            // highly unlikely that this class is loaded by the bootstrap class loader,
            // but handling anyways
            return Collections.list(ClassLoader.getSystemResources(resourceName));
        }
        return Collections.list(loader.getResources(resourceName));
    }

    @Nullable
    private static URL getResource(String resourceName) throws IOException {
        List<URL> urls = PluginDescriptorCache.getResources(resourceName);
        if (urls.isEmpty()) {
            return null;
        }
        if (urls.size() == 1) {
            return urls.get(0);
        }
        List<String> resourcePaths = Lists.newArrayList();
        for (URL url : urls) {
            resourcePaths.add("'" + url.getPath() + "'");
        }
        logger.error("more than one resource found with name '{}'. This file is only supported"
                + " inside of an informant packaged jar so there should be only one. Only using"
                + " the first one of {}.", resourceName, Joiner.on(", ").join(resourcePaths));
        return urls.get(0);
    }
}
