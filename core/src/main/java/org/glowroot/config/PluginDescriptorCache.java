/*
 * Copyright 2012-2014 the original author or authors.
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
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.ObjectMappers;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.MixinType;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginDescriptorCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginDescriptorCache.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;

    static PluginDescriptorCache create() {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        try {
            pluginDescriptors.addAll(readClasspathPluginDescriptors());
            pluginDescriptors.addAll(readStandalonePluginDescriptors());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
        }
        List<MixinType> mixinTypes = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.getAspects()) {
                try {
                    // don't initialize the aspect since that will trigger static initializers which
                    // will probably call PluginServices.get()
                    Class<?> aspectClass = Class.forName(aspect, false,
                            PluginDescriptor.class.getClassLoader());
                    advisors.addAll(getAdvisors(aspectClass));
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            advisors.addAll(DynamicAdviceGenerator.createAdvisors(pluginDescriptor.getPointcuts(),
                    pluginDescriptor.getId()));
        }
        return new PluginDescriptorCache(pluginDescriptors, mixinTypes, advisors);
    }

    static PluginDescriptorCache createInViewerMode() {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        try {
            pluginDescriptors.addAll(readClasspathPluginDescriptorsViewerMode());
            pluginDescriptors.addAll(readStandalonePluginDescriptors());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
        }
        List<PluginDescriptor> pluginDescriptorsWithoutAdvice = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(pluginDescriptor.copyWithoutAdvice());
        }
        return new PluginDescriptorCache(pluginDescriptorsWithoutAdvice,
                ImmutableList.<MixinType>of(), ImmutableList.<Advice>of());
    }

    private PluginDescriptorCache(List<PluginDescriptor> pluginDescriptors,
            List<MixinType> mixinTypes, List<Advice> advisors) {
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.advisors = ImmutableList.copyOf(advisors);
    }

    public ImmutableList<PluginDescriptor> getPluginDescriptors() {
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

    public ImmutableList<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    public ImmutableList<Advice> getAdvisors() {
        return advisors;
    }

    // the 2 methods below only used by test harness (LocalContainer), so that tests will still
    // succeed even if core is shaded (e.g. compiled from maven) and test-harness is compiled
    // against unshaded core (e.g. compiled previously in IDE)
    //
    // don't return ImmutableList
    @OnlyUsedByTests
    public List<MixinType> getMixinTypesNeverShaded() {
        return getMixinTypes();
    }

    // don't return ImmutableList, see comment above
    @OnlyUsedByTests
    public List<Advice> getAdvisorsNeverShaded() {
        return getAdvisors();
    }

    private static List<PluginDescriptor> readClasspathPluginDescriptors()
            throws IOException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        List<URL> urls = getResources("META-INF/glowroot.plugin.json");
        for (URL url : urls) {
            try {
                String content = Resources.toString(url, Charsets.UTF_8);
                PluginDescriptor pluginDescriptor =
                        ObjectMappers.readRequiredValue(mapper, content, PluginDescriptor.class);
                pluginDescriptors.add(pluginDescriptor);
            } catch (JsonProcessingException e) {
                logger.error("error parsing plugin descriptor: {}", url.toExternalForm(), e);
            }
        }
        return pluginDescriptors;
    }

    private static List<PluginDescriptor> readStandalonePluginDescriptors() throws IOException,
            URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (File pluginDescriptorFile : Plugins.getStandalonePluginDescriptorFiles()) {
            PluginDescriptor pluginDescriptor = ObjectMappers.readRequiredValue(mapper,
                    pluginDescriptorFile, PluginDescriptor.class);
            pluginDescriptors.add(pluginDescriptor);
        }
        return pluginDescriptors;
    }

    // plugin descriptors aren't really on classpath in viewer mode
    // so need to read them directly from the jar files
    private static List<PluginDescriptor> readClasspathPluginDescriptorsViewerMode()
            throws IOException, URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (File pluginJar : Plugins.getPluginJars()) {
            JarFile jarFile = new JarFile(pluginJar);
            JarEntry jarEntry = jarFile.getJarEntry("META-INF/glowroot.plugin.json");
            if (jarEntry == null) {
                continue;
            }
            InputStream jarEntryIn = jarFile.getInputStream(jarEntry);
            PluginDescriptor pluginDescriptor =
                    mapper.readValue(jarEntryIn, PluginDescriptor.class);
            pluginDescriptors.add(pluginDescriptor);
        }
        return pluginDescriptors;
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Pointcut pointcut = memberClass.getAnnotation(Pointcut.class);
            if (pointcut != null) {
                try {
                    advisors.add(Advice.from(pointcut, memberClass, false));
                } catch (AdviceConstructionException e) {
                    logger.error("error creating advice: {}", memberClass.getName(), e);
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

    private static ImmutableList<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = PluginDescriptorCache.class.getClassLoader();
        if (loader == null) {
            // highly unlikely that this class is loaded by the bootstrap class loader,
            // but handling anyways
            return ImmutableList.copyOf(Iterators.forEnumeration(
                    ClassLoader.getSystemResources(resourceName)));
        }
        return ImmutableList.copyOf(Iterators.forEnumeration(loader.getResources(resourceName)));
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
        logger.error("more than one resource found with name {}. This file is only supported"
                + " inside of an glowroot packaged jar so there should be only one. Only using"
                + " the first one of {}.", resourceName, Joiner.on(", ").join(resourcePaths));
        return urls.get(0);
    }
}
