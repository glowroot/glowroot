/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.transaction;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.advicegen.AdviceGenerator;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.api.weaving.Shim;
import org.glowroot.config.InstrumentationConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.Advice;
import org.glowroot.weaving.AdviceBuilder;
import org.glowroot.weaving.ClassLoaders;
import org.glowroot.weaving.LazyDefinedClass;
import org.glowroot.weaving.MixinType;
import org.glowroot.weaving.MixinTypeBase;
import org.glowroot.weaving.ShimType;
import org.glowroot.weaving.ShimTypeBase;

import static com.google.common.base.Preconditions.checkNotNull;

public class AdviceCache {

    private static final Logger logger = LoggerFactory.getLogger(AdviceCache.class);

    private static final AtomicInteger jarFileCounter = new AtomicInteger();

    private final ImmutableList<Advice> pluginAdvisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final @Nullable Instrumentation instrumentation;
    private final File baseDir;

    private volatile ImmutableList<Advice> reweavableAdvisors;
    private volatile ImmutableSet<String> reweavableConfigVersions;

    private volatile ImmutableList<Advice> allAdvisors;

    AdviceCache(List<PluginDescriptor> pluginDescriptors, List<File> pluginJars,
            List<InstrumentationConfig> reweavableConfigs,
            @Nullable Instrumentation instrumentation, File baseDir) throws Exception {

        List<Advice> pluginAdvisors = Lists.newArrayList();
        List<ShimType> shimTypes = Lists.newArrayList();
        List<MixinType> mixinTypes = Lists.newArrayList();
        Map<Advice, LazyDefinedClass> lazyAdvisors = Maps.newHashMap();
        // use temporary class loader so @Pointcut classes won't be defined for real until
        // PointcutClassVisitor is ready to weave them
        final URL[] pluginJarURLs = new URL[pluginJars.size()];
        for (int i = 0; i < pluginJars.size(); i++) {
            pluginJarURLs[i] = pluginJars.get(i).toURI().toURL();
        }
        ClassLoader tempIsolatedClassLoader = new IsolatedClassLoader(pluginJarURLs);
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class<?> aspectClass = Class.forName(aspect, false, tempIsolatedClassLoader);
                    pluginAdvisors.addAll(getAdvisors(aspectClass));
                    shimTypes.addAll(getShimTypes(aspectClass));
                    mixinTypes.addAll(getMixinTypes(aspectClass));
                } catch (ClassNotFoundException e) {
                    logger.warn("aspect not found: {}", aspect, e);
                }
            }
            lazyAdvisors.putAll(AdviceGenerator.createAdvisors(
                    pluginDescriptor.instrumentationConfigs(), pluginDescriptor.id()));
        }
        for (Entry<Advice, LazyDefinedClass> entry : lazyAdvisors.entrySet()) {
            pluginAdvisors.add(entry.getKey());
        }
        if (instrumentation == null) {
            // this is for tests that don't run with javaagent container
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            checkNotNull(loader, "Context class loader must be set");
            ClassLoaders.defineClassesInClassLoader(lazyAdvisors.values(), loader);
        } else {
            File generatedJarDir = new File(baseDir, "tmp");
            ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(generatedJarDir,
                    "plugin-pointcuts.jar");
            if (!lazyAdvisors.isEmpty()) {
                File jarFile = new File(generatedJarDir, "plugin-pointcuts.jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(lazyAdvisors.values(),
                        instrumentation, jarFile);
            }
        }
        this.pluginAdvisors = ImmutableList.copyOf(pluginAdvisors);
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.instrumentation = instrumentation;
        this.baseDir = baseDir;
        updateAdvisors(reweavableConfigs, true);
    }

    Supplier<List<Advice>> getAdvisorsSupplier() {
        return new Supplier<List<Advice>>() {
            @Override
            public List<Advice> get() {
                return allAdvisors;
            }
        };
    }

    @VisibleForTesting
    public List<ShimType> getShimTypes() {
        return shimTypes;
    }

    @VisibleForTesting
    public List<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    @EnsuresNonNull({"reweavableAdvisors", "reweavableConfigVersions", "allAdvisors"})
    public void updateAdvisors(/*>>>@UnknownInitialization(AdviceCache.class) AdviceCache this,*/
            List<InstrumentationConfig> reweavableConfigs, boolean cleanTmpDir) throws Exception {
        ImmutableMap<Advice, LazyDefinedClass> advisors =
                AdviceGenerator.createAdvisors(reweavableConfigs, null);
        if (instrumentation == null) {
            // this is for tests that don't run with javaagent container
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            checkNotNull(loader, "Context class loader must be set");
            ClassLoaders.defineClassesInClassLoader(advisors.values(), loader);
        } else {
            File generatedJarDir = new File(baseDir, "tmp");
            if (cleanTmpDir) {
                ClassLoaders.createDirectoryOrCleanPreviousContentsWithPrefix(generatedJarDir,
                        "config-pointcuts");
            }
            if (!advisors.isEmpty()) {
                String suffix = "";
                int count = jarFileCounter.incrementAndGet();
                if (count > 1) {
                    suffix = "-" + count;
                }
                File jarFile = new File(generatedJarDir, "config-pointcuts" + suffix + ".jar");
                ClassLoaders.defineClassesInBootstrapClassLoader(advisors.values(),
                        instrumentation, jarFile);
            }
        }
        reweavableAdvisors = advisors.keySet().asList();
        reweavableConfigVersions = createReweavableConfigVersions(reweavableConfigs);
        allAdvisors = ImmutableList.copyOf(Iterables.concat(pluginAdvisors, reweavableAdvisors));
    }

    public boolean isOutOfSync(List<InstrumentationConfig> reweavableConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (InstrumentationConfig reweavableConfig : reweavableConfigs) {
            versions.add(reweavableConfig.version());
        }
        return !versions.equals(this.reweavableConfigVersions);
    }

    private static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Pointcut.class)) {
                try {
                    advisors.add(new AdviceBuilder(memberClass, false).build());
                } catch (Exception e) {
                    logger.error("error creating advice: {}", memberClass.getName(), e);
                }
            }
        }
        return advisors;
    }

    private static List<ShimType> getShimTypes(Class<?> aspectClass) throws IOException {
        List<ShimType> shimTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Shim shim = memberClass.getAnnotation(Shim.class);
            if (shim != null) {
                shimTypes.add(ShimTypeBase.from(shim, memberClass));
            }
        }
        return shimTypes;
    }

    private static List<MixinType> getMixinTypes(Class<?> aspectClass) throws IOException {
        List<MixinType> mixinTypes = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            Mixin mixin = memberClass.getAnnotation(Mixin.class);
            if (mixin != null) {
                mixinTypes.add(MixinTypeBase.from(mixin, memberClass));
            }
        }
        return mixinTypes;
    }

    private static ImmutableSet<String> createReweavableConfigVersions(
            List<InstrumentationConfig> reweavableConfigs) {
        Set<String> versions = Sets.newHashSet();
        for (InstrumentationConfig reweavableConfig : reweavableConfigs) {
            versions.add(reweavableConfig.version());
        }
        return ImmutableSet.copyOf(versions);
    }

    // this method exists because tests cannot use (sometimes) shaded guava Supplier
    @OnlyUsedByTests
    public List<Advice> getAdvisors() {
        return getAdvisorsSupplier().get();
    }
}
