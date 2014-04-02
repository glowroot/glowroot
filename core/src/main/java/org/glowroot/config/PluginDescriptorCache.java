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
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.ObjectMappers;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.MixinType;

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

    public static PluginDescriptorCache create() {
        ImmutableList.Builder<PluginDescriptor> thePluginDescriptors = ImmutableList.builder();
        try {
            thePluginDescriptors.addAll(readClasspathPluginDescriptors());
            thePluginDescriptors.addAll(readStandalonePluginDescriptors());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
        }
        ImmutableList<PluginDescriptor> pluginDescriptors = thePluginDescriptors.build();

        ImmutableList.Builder<MixinType> theMixinTypes = ImmutableList.builder();
        ImmutableList.Builder<Advice> theAdvisors = ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.getAspects()) {
                try {
                    // don't initialize the aspect since that will trigger static initializers which
                    // will probably call PluginServices.get()
                    Class<?> aspectClass = Class.forName(aspect, false,
                            PluginDescriptor.class.getClassLoader());
                    theAdvisors.addAll(getAdvisors(aspectClass));
                    theMixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            theAdvisors.addAll(DynamicAdviceGenerator.getAdvisors(pluginDescriptor.getPointcuts(),
                    pluginDescriptor.getId()));
        }
        return new PluginDescriptorCache(pluginDescriptors, theMixinTypes.build(),
                theAdvisors.build());
    }

    public static PluginDescriptorCache createInViewerMode() {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        try {
            pluginDescriptors.addAll(readClasspathPluginDescriptorsViewerMode());
            pluginDescriptors.addAll(readStandalonePluginDescriptors());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
        }
        ImmutableList.Builder<PluginDescriptor> pluginDescriptorsWithoutAdvice =
                ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(pluginDescriptor.copyWithoutAdvice());
        }
        return new PluginDescriptorCache(pluginDescriptorsWithoutAdvice.build(),
                ImmutableList.<MixinType>of(), ImmutableList.<Advice>of());
    }

    private PluginDescriptorCache(ImmutableList<PluginDescriptor> pluginDescriptors,
            ImmutableList<MixinType> mixinTypes, ImmutableList<Advice> advisors) {
        this.pluginDescriptors = pluginDescriptors;
        this.mixinTypes = mixinTypes;
        this.advisors = advisors;
    }

    // don't return ImmutableList since this method is used by UiTestingMain and when UiTestingMain
    // is compiled by maven, it is compiled against shaded glowroot, but then if it is run inside
    // an IDE without rebuilding UiTestingMain it will fail since glowroot is then unshaded
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
    // SameJvmExecutionAdapter is compiled by maven, it is compiled against shaded glowroot,
    // but then if a unit test is run inside an IDE without rebuilding SameJvmExecutionAdapter it
    // will fail since glowroot is then unshaded
    @Immutable
    public List<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    // don't return ImmutableList, see comment above
    @Immutable
    public List<Advice> getAdvisors() {
        return advisors;
    }

    private static ImmutableList<PluginDescriptor> readClasspathPluginDescriptors()
            throws IOException {
        ImmutableList.Builder<PluginDescriptor> pluginDescriptors = ImmutableList.builder();
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
        return pluginDescriptors.build();
    }

    private static ImmutableList<PluginDescriptor> readStandalonePluginDescriptors()
            throws IOException, URISyntaxException {
        ImmutableList.Builder<PluginDescriptor> pluginDescriptors = ImmutableList.builder();
        for (File pluginDescriptorFile : Plugins.getStandalonePluginDescriptorFiles()) {
            PluginDescriptor pluginDescriptor = ObjectMappers.readRequiredValue(mapper,
                    pluginDescriptorFile, PluginDescriptor.class);
            pluginDescriptors.add(pluginDescriptor);
        }
        return pluginDescriptors.build();
    }

    // plugin descriptors aren't really on classpath in viewer mode
    // so need to read them directly from the jar files
    private static ImmutableList<PluginDescriptor> readClasspathPluginDescriptorsViewerMode()
            throws IOException, URISyntaxException {
        ImmutableList.Builder<PluginDescriptor> pluginDescriptors = ImmutableList.builder();
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
        return pluginDescriptors.build();
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
        logger.error("more than one resource found with name {}. This file is only supported"
                + " inside of an glowroot packaged jar so there should be only one. Only using"
                + " the first one of {}.", resourceName, Joiner.on(", ").join(resourcePaths));
        return urls.get(0);
    }
}
