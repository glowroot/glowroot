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
package org.glowroot.weaving;

import java.lang.instrument.ClassFileTransformer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeavingClassFileTransformer implements ClassFileTransformer {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassFileTransformer.class);

    private final Weaver weaver;

    private final boolean weaveBootstrapClassLoader;

    // because of the crazy pre-initialization of javaagent classes (see
    // org.glowroot.core.weaving.PreInitializeClasses), all inputs into this class should be
    // concrete, non-subclassed types so that the correct set of used classes can be computed (see
    // calculation in the test class org.glowroot.weaving.preinit.GlobalCollector, and
    // hard-coded results in org.glowroot.weaving.PreInitializeWeavingClassesTest)
    // note: an exception is made for WeavingTimerService, see PreInitializeWeavingClassesTest for
    // explanation
    public WeavingClassFileTransformer(List<MixinType> mixinTypes, Supplier<List<Advice>> advisors,
            AnalyzedWorld analyzedWorld, WeavingTimerService weavingTimerService,
            boolean metricWrapperMethods) {
        weaver = new Weaver(advisors, mixinTypes, analyzedWorld, weavingTimerService,
                metricWrapperMethods);
        // can only weave classes in bootstrap class loader if glowroot is in bootstrap class
        // loader, otherwise woven bootstrap classes will generate NoClassDefFoundError since
        // the woven code will not be able to see glowroot classes (e.g. PluginServices)
        weaveBootstrapClassLoader = isInBootstrapClassLoader();
    }

    // From the javadoc on ClassFileTransformer.transform():
    // "throwing an exception has the same effect as returning null"
    //
    // so all exceptions must be caught and logged here or they will be lost
    @Override
    public byte /*@Nullable*/[] transform(@Nullable ClassLoader loader,
            @Nullable String className, @Nullable Class<?> classBeingRedefined,
            @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
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

    private byte /*@Nullable*/[] transformInternal(@Nullable ClassLoader loader, String className,
            @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
        // don't weave glowroot core classes, including shaded classes like h2 jdbc driver
        if (isGlowrootCoreClass(className)) {
            return null;
        }
        if (className.startsWith("sun/reflect/Generated")) {
            // optimization, no need to try to weave the many classes generated for reflection:
            // sun/reflect/GeneratedSerializationConstructorAccessor..
            // sun/reflect/GeneratedConstructorAccessor..
            // sun/reflect/GeneratedMethodAccessor..
            return null;
        }
        if (loader == null && !weaveBootstrapClassLoader) {
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

    private static boolean isGlowrootCoreClass(String className) {
        // can't just match "org/glowroot/" since that would match glowroot plugins
        // (and integration test classes)
        return className.startsWith("org/glowroot/api/")
                || className.startsWith("org/glowroot/advicegen/")
                || className.startsWith("org/glowroot/collector/")
                || className.startsWith("org/glowroot/common/")
                || className.startsWith("org/glowroot/config/")
                || className.startsWith("org/glowroot/jvm/")
                || className.startsWith("org/glowroot/local/")
                || className.startsWith("org/glowroot/shaded/")
                || className.startsWith("org/glowroot/transaction/")
                || className.startsWith("org/glowroot/weaving/");
    }

    private static boolean isInBootstrapClassLoader() {
        return WeavingClassFileTransformer.class.getClassLoader() == null;
    }
}
