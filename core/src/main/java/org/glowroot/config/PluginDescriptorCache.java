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
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.ObjectMappers;
import org.glowroot.dynamicadvice.DynamicAdviceGenerator;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.Advice.AdviceConstructionException;
import org.glowroot.weaving.ClassLoaders;
import org.glowroot.weaving.LazyDefinedClass;
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

    static PluginDescriptorCache create(@Nullable PluginResourceFinder pluginResourceFinder,
            @Nullable Instrumentation instrumentation, File dataDir) throws IOException,
            URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(pluginResourceFinder);
        List<MixinType> mixinTypes = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        Map<Advice, LazyDefinedClass> lazyAdvisors = Maps.newHashMap();
        // use temporary class loader so @Pointcut classes won't be defined for real until
        // PointcutClassVisitor is ready to weave them
        ClassLoader tempIsolatedClassLoader = new IsolatedClassLoader(pluginResourceFinder);
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.getAspects()) {
                try {
                    Class<?> aspectClass = Class.forName(aspect, false, tempIsolatedClassLoader);
                    advisors.addAll(getAdvisors(aspectClass));
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            lazyAdvisors.putAll(DynamicAdviceGenerator.createAdvisors(
                    pluginDescriptor.getPointcuts(), pluginDescriptor.getId()));
        }
        for (Entry<Advice, LazyDefinedClass> entry : lazyAdvisors.entrySet()) {
            advisors.add(entry.getKey());
        }
        if (instrumentation == null) {
            // this is for tests that don't run with javaagent container
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                throw new AssertionError("Context class loader must be set");
            }
            ClassLoaders.defineClassesInClassLoader(lazyAdvisors.values(), loader);
        } else {
            File generatedJarDir = new File(dataDir, "tmp");
            ClassLoaders.cleanPreviouslyGeneratedJars(generatedJarDir, "plugin-pointcuts.jar");
            File jarFile = new File(generatedJarDir, "plugin-pointcuts.jar");
            ClassLoaders.defineClassesInBootstrapClassLoader(lazyAdvisors.values(),
                    instrumentation, jarFile);
        }
        return new PluginDescriptorCache(pluginDescriptors, mixinTypes, advisors);
    }

    static PluginDescriptorCache createInViewerMode(
            @Nullable PluginResourceFinder pluginResourceFinder) throws IOException,
            URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(pluginResourceFinder);
        List<PluginDescriptor> pluginDescriptorsWithoutAdvice = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(pluginDescriptor.copyWithoutAdvice());
        }
        return new PluginDescriptorCache(pluginDescriptorsWithoutAdvice,
                ImmutableList.<MixinType>of(), ImmutableList.<Advice>of());
    }

    private PluginDescriptorCache(List<PluginDescriptor> pluginDescriptors,
            List<MixinType> mixinTypes, List<Advice> advisors) {
        // sorted for display to console during startup and for plugin config sidebar menu
        this.pluginDescriptors =
                PluginDescriptor.specialOrderingByName.immutableSortedCopy(pluginDescriptors);
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

    private static List<PluginDescriptor> readPluginDescriptors(
            @Nullable PluginResourceFinder pluginResourceFinder) throws IOException,
            URISyntaxException {
        Set<URL> urls = Sets.newHashSet();
        // add descriptors on the class path, this is primarily for integration tests
        urls.addAll(getResources("META-INF/glowroot.plugin.json"));
        if (pluginResourceFinder != null) {
            urls.addAll(pluginResourceFinder.getDescriptorURLs());
        }
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
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

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Pointcut.class)) {
                try {
                    advisors.add(Advice.from(memberClass, false));
                } catch (AdviceConstructionException e) {
                    logger.error("error creating advice: {}", memberClass.getName(), e);
                }
            }
        }
        return advisors;
    }

    private static List<MixinType> getMixinTypes(Class<?> aspectClass) throws IOException {
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
            return ImmutableList.copyOf(Iterators.forEnumeration(
                    ClassLoader.getSystemResources(resourceName)));
        }
        return ImmutableList.copyOf(Iterators.forEnumeration(loader.getResources(resourceName)));
    }
}
