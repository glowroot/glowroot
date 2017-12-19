/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WeavingClassFileTransformer implements ClassFileTransformer {

    private static final boolean ALLOW_WEAVING_AGENT_CLASSES =
            Boolean.getBoolean("glowroot.internal.allowWeavingAgentClasses");

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassFileTransformer.class);

    private final Weaver weaver;
    private final Instrumentation instrumentation;

    private final boolean weaveBootstrapClassLoader;

    // not using the much more convenient (and concurrent) guava CacheBuilder since it uses many
    // additional classes that must then be pre-initialized since this is called from inside
    // ClassFileTransformer.transform() (see PreInitializeClasses)
    private final Set<Object> redefinedModules = Collections
            .newSetFromMap(Collections.synchronizedMap(new WeakHashMap<Object, Boolean>()));

    // because of the crazy pre-initialization of javaagent classes (see
    // org.glowroot.core.weaving.PreInitializeClasses), all inputs into this class should be
    // concrete, non-subclassed types so that the correct set of used classes can be computed (see
    // calculation in the test class org.glowroot.weaving.preinit.GlobalCollector, and
    // hard-coded results in org.glowroot.weaving.PreInitializeWeavingClassesTest)
    // note: an exception is made for WeavingTimerService, see PreInitializeWeavingClassesTest for
    // explanation
    public WeavingClassFileTransformer(Weaver weaver, Instrumentation instrumentation) {
        this.weaver = weaver;
        this.instrumentation = instrumentation;
        // can only weave classes in bootstrap class loader if glowroot is in bootstrap class
        // loader, otherwise woven bootstrap classes will generate NoClassDefFoundError since
        // the woven code will not be able to see glowroot classes
        // (e.g. woven code will not be able to see org.glowroot.agent.plugin.api.Agent)
        weaveBootstrapClassLoader = isInBootstrapClassLoader();
    }

    // this method is called by the Java 9 transform method that passes in a Module
    // see Java9HackClassFileTransformer
    public byte /*@Nullable*/ [] transformJava9(Object module, @Nullable ClassLoader loader,
            @Nullable String className, @Nullable Class<?> classBeingRedefined,
            @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
        if (redefinedModules.add(module)) {
            try {
                Java9.grantAccessToGlowroot(instrumentation, module);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        return transform(loader, className, classBeingRedefined, protectionDomain, bytes);
    }

    // From the javadoc on ClassFileTransformer.transform():
    // "throwing an exception has the same effect as returning null"
    //
    // so all exceptions must be caught and logged here or they will be lost
    @Override
    public byte /*@Nullable*/ [] transform(@Nullable ClassLoader loader, @Nullable String className,
            @Nullable Class<?> classBeingRedefined, @Nullable ProtectionDomain protectionDomain,
            byte[] bytes) {
        // internal subclasses of MethodHandle are passed in with null className
        // (see integration test MethodHandleWeavingTest for more detail)
        // also, more importantly, Java 8 lambdas are passed in with null className, which need to
        // be woven by executor plugin
        String nonNullClassName = className == null ? "unnamed" : className;
        try {
            return transformInternal(loader, nonNullClassName, protectionDomain, bytes);
        } catch (Throwable t) {
            // see method-level comment
            logger.error("error weaving {}: {}", nonNullClassName, t.getMessage(), t);
            return null;
        }
    }

    private byte /*@Nullable*/ [] transformInternal(@Nullable ClassLoader loader, String className,
            @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
        if (ignoreClass(className)) {
            return null;
        }
        if (loader == null && !weaveBootstrapClassLoader) {
            // can only weave classes in bootstrap class loader if glowroot is in bootstrap class
            // loader, otherwise woven bootstrap classes will generate NoClassDefFoundError since
            // the woven code will not be able to see glowroot classes
            // (e.g. woven code will not be able to see org.glowroot.agent.plugin.api.Agent)
            return null;
        }
        CodeSource codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
        return weaver.weave(bytes, className, codeSource, loader);
    }

    private static boolean ignoreClass(String className) {
        if (!ALLOW_WEAVING_AGENT_CLASSES && isGlowrootAgentClass(className)) {
            // don't weave glowroot core classes, including shaded classes like h2 jdbc driver
            return true;
        }
        if (className.startsWith("sun/reflect/Generated")) {
            // optimization, no need to try to weave the many classes generated for reflection:
            // sun/reflect/GeneratedSerializationConstructorAccessor..
            // sun/reflect/GeneratedConstructorAccessor..
            // sun/reflect/GeneratedMethodAccessor..
            return true;
        }
        // proxies under JDK 6 start with $Proxy
        // proxies under JDK 7+ start with com/sun/proxy/$Proxy
        if (className.startsWith("com/sun/proxy/$Proxy") || className.startsWith("$Proxy")) {
            // optimization, especially for jdbc plugin to avoid weaving proxy wrappers when dealing
            // with connection pools
            return true;
        }
        return false;
    }

    private static boolean isGlowrootAgentClass(String className) {
        if (!className.startsWith("org/glowroot")) {
            // optimization for common case
            return false;
        }
        return className.startsWith("org/glowroot/common/")
                || className.startsWith("org/glowroot/ui/")
                || className.startsWith("org/glowroot/wire/api/")
                || className.startsWith("org/glowroot/agent/api/")
                || className.startsWith("org/glowroot/agent/plugin/api/")
                || className.startsWith("org/glowroot/agent/central/")
                || className.startsWith("org/glowroot/agent/collector/")
                || className.startsWith("org/glowroot/agent/config/")
                || className.startsWith("org/glowroot/agent/embedded/init/")
                || className.startsWith("org/glowroot/agent/embedded/repo/")
                || className.startsWith("org/glowroot/agent/embedded/util/")
                || className.startsWith("org/glowroot/agent/impl/")
                || className.startsWith("org/glowroot/agent/init/")
                || className.startsWith("org/glowroot/agent/jul/")
                || className.startsWith("org/glowroot/agent/live/")
                || className.startsWith("org/glowroot/agent/model/")
                || className.startsWith("org/glowroot/agent/shaded/")
                || className.startsWith("org/glowroot/agent/sql/")
                || className.startsWith("org/glowroot/agent/util/")
                || className.startsWith("org/glowroot/agent/weaving/");
    }

    private static boolean isInBootstrapClassLoader() {
        return WeavingClassFileTransformer.class.getClassLoader() == null;
    }
}
