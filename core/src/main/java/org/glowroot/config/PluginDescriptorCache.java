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

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.advicegen.AdviceGenerator;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.AdviceBuilder;
import org.glowroot.weaving.AdviceBuilder.AdviceConstructionException;
import org.glowroot.weaving.ClassLoaders;
import org.glowroot.weaving.LazyDefinedClass;
import org.glowroot.weaving.MixinType;

@Value.Immutable
public abstract class PluginDescriptorCache {

    private static final Logger logger = LoggerFactory.getLogger(PluginDescriptorCache.class);

    public abstract List<PluginDescriptor> pluginDescriptors();
    public abstract List<MixinType> mixinTypes();
    public abstract List<Advice> advisors();

    static PluginDescriptorCache create(PluginCache pluginCache,
            @Nullable Instrumentation instrumentation, File dataDir) throws IOException,
            URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(pluginCache);
        List<MixinType> mixinTypes = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        Map<Advice, LazyDefinedClass> lazyAdvisors = Maps.newHashMap();
        // use temporary class loader so @Pointcut classes won't be defined for real until
        // PointcutClassVisitor is ready to weave them
        URL[] pluginJars = new URL[pluginCache.getPluginJars().size()];
        for (int i = 0; i < pluginCache.getPluginJars().size(); i++) {
            pluginJars[i] = pluginCache.getPluginJars().get(i).toURI().toURL();
        }
        ClassLoader tempIsolatedClassLoader = new IsolatedClassLoader(pluginJars);
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class<?> aspectClass = Class.forName(aspect, false, tempIsolatedClassLoader);
                    advisors.addAll(getAdvisors(aspectClass));
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            lazyAdvisors.putAll(AdviceGenerator.createAdvisors(
                    pluginDescriptor.capturePoints(), pluginDescriptor.id()));
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
            if (!lazyAdvisors.isEmpty()) {
                File jarFile = new File(generatedJarDir, "plugin-pointcuts.jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(lazyAdvisors.values(),
                        instrumentation, jarFile);
            }
        }
        return ImmutablePluginDescriptorCache.builder()
                .addAllPluginDescriptors(sorted(pluginDescriptors))
                .addAllMixinTypes(mixinTypes)
                .addAllAdvisors(advisors)
                .build();
    }

    static PluginDescriptorCache createInViewerMode(PluginCache pluginCache) throws IOException,
            URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = readPluginDescriptors(pluginCache);
        List<PluginDescriptor> pluginDescriptorsWithoutAdvice = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginDescriptorsWithoutAdvice.add(pluginDescriptor.copyWithoutAdvice());
        }
        return ImmutablePluginDescriptorCache.builder()
                .addAllPluginDescriptors(sorted(pluginDescriptorsWithoutAdvice))
                .build();
    }

    private static List<PluginDescriptor> readPluginDescriptors(PluginCache pluginCache)
            throws IOException, URISyntaxException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (URL url : pluginCache.getDescriptorURLs()) {
            try {
                String content = Resources.toString(url, Charsets.UTF_8);
                PluginDescriptor pluginDescriptor =
                        Marshaling.fromJson(content, PluginDescriptor.class);
                pluginDescriptors.add(pluginDescriptor);
            } catch (JsonProcessingException e) {
                logger.error("error parsing plugin descriptor: {}", url.toExternalForm(), e);
            }
        }
        return pluginDescriptors;
    }

    // sorted for display to console during startup and for plugin config sidebar menu
    private static List<PluginDescriptor> sorted(List<PluginDescriptor> pluginDescriptors) {
        return PluginDescriptor.specialOrderingByName.immutableSortedCopy(pluginDescriptors);
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Pointcut.class)) {
                try {
                    advisors.add(new AdviceBuilder(memberClass, false).build());
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
}
