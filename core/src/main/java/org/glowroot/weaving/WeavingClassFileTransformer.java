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
package org.glowroot.weaving;

import java.lang.instrument.ClassFileTransformer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class WeavingClassFileTransformer implements ClassFileTransformer {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassFileTransformer.class);

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> pluginAdvisors;
    private final Supplier<ImmutableList<Advice>> pointcutConfigAdvisors;

    private final ParsedTypeCache parsedTypeCache;
    private final WeavingTimerService traceMetricTimerService;
    private final boolean traceMetricWrapperMethods;

    // it is important to only have a single weaver per class loader because storing state of each
    // previously parsed class in order to re-construct class hierarchy in case one or more .class
    // files aren't available via ClassLoader.getResource()
    //
    // weak keys to prevent retention of class loaders
    private final LoadingCache<ClassLoader, Weaver> weavers =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<ClassLoader, Weaver>() {
                        @Override
                        public Weaver load(ClassLoader loader) {
                            return new Weaver(mixinTypes, pluginAdvisors, pointcutConfigAdvisors,
                                    parsedTypeCache, traceMetricTimerService,
                                    traceMetricWrapperMethods);
                        }
                    });
    // the weaver for the bootstrap class loader (null) has to be stored separately since
    // LoadingCache doesn't accept null keys, and using an Optional<ClassLoader> for the key makes
    // the weakness on the Optional instance instead of on the ClassLoader instance
    @Nullable
    private final Weaver bootstrapLoaderWeaver;

    // because of the crazy pre-initialization of javaagent classes (see
    // org.glowroot.core.weaving.PreInitializeClasses), all inputs into this class should be
    // concrete, non-subclassed types so that the correct set of used classes can be computed (see
    // calculation in the test class org.glowroot.weaving.preinit.GlobalCollector, and
    // hard-coded results in org.glowroot.weaving.PreInitializeWeavingClassesTest)
    // note: an exception is made for WeavingTimerService, see PreInitializeWeavingClassesTest for
    // explanation
    public WeavingClassFileTransformer(List<MixinType> mixinTypes, List<Advice> pluginAdvisors,
            Supplier<ImmutableList<Advice>> pointcutConfigAdvisors,
            ParsedTypeCache parsedTypeCache, WeavingTimerService weavingTimerService,
            boolean traceMetricWrapperMethods) {
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.pluginAdvisors = ImmutableList.copyOf(pluginAdvisors);
        this.pointcutConfigAdvisors = pointcutConfigAdvisors;
        this.parsedTypeCache = parsedTypeCache;
        this.traceMetricTimerService = weavingTimerService;
        this.traceMetricWrapperMethods = traceMetricWrapperMethods;
        if (isInBootstrapClassLoader()) {
            // can only weave classes in bootstrap class loader if glowroot is in bootstrap class
            // loader, otherwise woven bootstrap classes will generate NoClassDefFoundError since
            // the woven code will not be able to see glowroot classes (e.g. PluginServices)
            bootstrapLoaderWeaver = new Weaver(this.mixinTypes, this.pluginAdvisors,
                    this.pointcutConfigAdvisors, parsedTypeCache, weavingTimerService,
                    traceMetricWrapperMethods);
        } else {
            bootstrapLoaderWeaver = null;
        }
    }

    // From the javadoc on ClassFileTransformer.transform():
    // "throwing an exception has the same effect as returning null"
    //
    // so all exceptions must be caught and logged here or they will be lost
    @Override
    public byte/*@Nullable*/[] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        if (className == null) {
            // internal subclasses of MethodHandle are passed in with null className
            // (see integration test MethodHandleWeavingTest for more detail)
            return null;
        }
        try {
            return transformInternal(loader, className, protectionDomain, bytes);
        } catch (Throwable t) {
            // see method-level comment
            logger.error(t.getMessage(), t);
            return null;
        }
    }

    private byte/*@Nullable*/[] transformInternal(@Nullable ClassLoader loader, String className,
            @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
        // don't weave glowroot classes, including shaded classes like h2 jdbc driver
        // (can't just match "org/glowroot/" since that would match integration test classes)
        if (className.startsWith("org/glowroot/collector/")
                || className.startsWith("org/glowroot/common/")
                || className.startsWith("org/glowroot/config/")
                || className.startsWith("org/glowroot/dynamicadvice/")
                || className.startsWith("org/glowroot/local/")
                || className.startsWith("org/glowroot/shaded/")
                || className.startsWith("org/glowroot/trace/")
                || className.startsWith("org/glowroot/weaving/")) {
            return null;
        }
        if (className.startsWith("sun/reflect/Generated")) {
            // optimization, no need to try to weave the many classes generated for reflection:
            // sun/reflect/GeneratedSerializationConstructorAccessor..
            // sun/reflect/GeneratedConstructorAccessor..
            // sun/reflect/GeneratedMethodAccessor..
            return null;
        }
        Weaver weaver = getWeaver(loader);
        if (weaver == null) {
            // can only weave classes in bootstrap class loader if glowroot is in bootstrap class
            // loader, otherwise woven bootstrap classes will generate NoClassDefFoundError since
            // the woven code will not be able to see glowroot classes (e.g. PluginServices)
            return null;
        }
        logger.trace("transform(): className={}", className);
        CodeSource codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
        byte[] transformedBytes = weaver.weave(bytes, className, codeSource, loader);
        if (transformedBytes != null) {
            logger.debug("transform(): transformed {}", className);
        }
        return transformedBytes;
    }

    @Nullable
    private Weaver getWeaver(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootstrapLoaderWeaver;
        } else {
            return weavers.getUnchecked(loader);
        }
    }

    public static boolean isInBootstrapClassLoader() {
        return WeavingClassFileTransformer.class.getClassLoader() == null;
    }
}
