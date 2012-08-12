/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Timer;
import org.informantproject.api.weaving.Mixin;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.trace.MetricImpl;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class InformantClassFileTransformer implements ClassFileTransformer {

    private static final Logger logger = LoggerFactory
            .getLogger(InformantClassFileTransformer.class);

    private final ImmutableList<Mixin> mixins;
    private final ImmutableList<Advice> advisors;

    private final ParsedTypeCache parsedTypeCache = new ParsedTypeCache();

    // it is important to only have a single weaver per class loader because storing state of each
    // previously parsed class in order to re-construct class hierarchy in case one or more .class
    // files aren't available via ClassLoader.getResource()
    //
    // weak keys to prevent retention of class loaders
    private final LoadingCache<Optional<ClassLoader>, Weaver> weavers =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Optional<ClassLoader>, Weaver>() {
                        @Override
                        public Weaver load(Optional<ClassLoader> loader) {
                            return new Weaver(mixins, advisors, loader.orNull(), parsedTypeCache);
                        }
                    });

    private final PluginServices pluginServices;
    private final Metric metric;

    public InformantClassFileTransformer(PluginServices pluginServices, Ticker ticker) {
        ImmutableList.Builder<Mixin> mixins = ImmutableList.builder();
        ImmutableList.Builder<Advice> advisors = ImmutableList.builder();
        for (PluginDescriptor plugin : Plugins.getPackagedPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        this.mixins = mixins.build();
        this.advisors = advisors.build();
        this.pluginServices = pluginServices;
        metric = new MetricImpl("informant weaving", ticker);
        loadUsedTypes();
    }

    public byte[] transform(@Nullable ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] bytes)
            throws IllegalClassFormatException {

        return transform$informant$metric$informant$weaving$0(loader, className,
                protectionDomain, bytes);
    }

    // weird method name is following "metric marker" method naming
    private byte[] transform$informant$metric$informant$weaving$0(@Nullable ClassLoader loader,
            String className, ProtectionDomain protectionDomain, byte[] bytes) {

        Timer timer = pluginServices.startTimer(metric);
        try {
            Weaver weaver = weavers.getUnchecked(Optional.fromNullable(loader));
            byte[] transformedBytes = weaver.weave(bytes, protectionDomain);
            if (transformedBytes != bytes) {
                logger.debug("transform(): transformed {}", className);
            }
            return transformedBytes;
        } finally {
            timer.end();
        }
    }

    // "There are some things that agents are allowed to do that simply should not be permitted"
    // -- http://mail.openjdk.java.net/pipermail/hotspot-dev/2012-March/005464.html
    //
    // In particular (at least prior to parallel class loading in JDK 7) loading other classes
    // inside of a ClassFileTransformer.transform() method occasionally leads to deadlocks. To avoid
    // loading other classes inside of the transform() method, all classes referenced from
    // InformantClassFileTransformer are preloaded (and all classes referenced from those classes,
    // etc).
    //
    // It seems safe to stop the recursion at classes in the bootstrap classloader, and this
    // optimization brings the preloading time down from ~760 to ~230 milliseconds.
    //
    // It's tempting to further optimize this by hard-coding the list of classes to load, but the
    // list of classes to load is sensitive to how the code was compiled. For example, compilation
    // under javac (JDK 6) results in an empty anonymous inner class InformantClassFileTransformer$1
    // while compilation under eclipse (Juno) doesn't create this empty anonymous inner class. So it
    // seems safer to calculate the list of classes to load at runtime.
    //
    private static void loadUsedTypes() {
        try {
            new UsedTypeCollector().processType(InformantClassFileTransformer.class.getName());
        } catch (ClassNotFoundException e) {
            logger.error(e.getMessage(), e);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static class UsedTypeCollector extends Remapper {

        private static final ClassLoader bootstrapClassLoader = Object.class.getClassLoader();

        private final Set<String> typeNames = Sets.newHashSet();
        private final Set<String> bootstrapClassLoaderTypeNames = Sets.newHashSet();

        @Override
        public String map(String internalTypeName) {
            String typeName = internalTypeName.replace('/', '.');
            if (bootstrapClassLoaderTypeNames.contains(typeName)) {
                // already processed this type
                return internalTypeName;
            } else if (typeNames.contains(typeName)) {
                // already processed this type
                return internalTypeName;
            } else {
                try {
                    processType(typeName);
                } catch (ClassNotFoundException e) {
                    logger.error(e.getMessage(), e);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
                return internalTypeName;
            }
        }

        private void processType(String typeName) throws ClassNotFoundException, IOException {
            if (Class.forName(typeName).getClassLoader() == bootstrapClassLoader) {
                // ignore bootstrap classloader types
                bootstrapClassLoaderTypeNames.add(typeName);
            } else {
                typeNames.add(typeName);
                ClassReader cr = new ClassReader(typeName);
                RemappingClassAdapter visitor = new RemappingClassAdapter(null, this);
                cr.accept(visitor, 0);
            }
        }
    }
}
