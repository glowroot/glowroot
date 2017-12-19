/*
 * Copyright 2011-2017 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.reflect.Reflection;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.impl.OptionalThreadContextImpl;
import org.glowroot.agent.impl.ServiceRegistryImpl;
import org.glowroot.agent.impl.ThreadContextImpl;
import org.glowroot.agent.impl.ThreadContextThreadLocal;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.model.ThreadContextPlus;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.common.util.OnlyUsedByTests;

// the placement of this code in the main Glowroot code base (and not inside of the tests folder) is
// not ideal, but the alternative is to create a separate artifact (or at least classifier) for this
// small amount of code, which also seems to be not ideal
@OnlyUsedByTests
public class IsolatedWeavingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedWeavingClassLoader.class);

    // bridge classes can be either interfaces or base classes
    private final ImmutableList<Class<?>> bridgeClasses;
    private final Map<String, Class<?>> classes = Maps.newConcurrentMap();
    private final Map<String, Package> packages = Maps.newConcurrentMap();

    private final Map<String, byte[]> manualClasses = Maps.newConcurrentMap();

    private volatile @MonotonicNonNull Weaver weaver;

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> inWeaving = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public IsolatedWeavingClassLoader(Class<?>... bridgeClasses) {
        super(IsolatedWeavingClassLoader.class.getClassLoader());
        this.bridgeClasses = ImmutableList.<Class<?>>builder()
                .add(bridgeClasses)
                .add(IsolatedWeavingClassLoader.class)
                .add(Weaver.class)
                .build();
    }

    public void setWeaver(Weaver weaver) {
        this.weaver = weaver;
    }

    public void addManualClass(String name, byte[] bytes) {
        manualClasses.put(name, bytes);
    }

    public <S, T extends S> S newInstance(Class<T> implClass, Class<S> bridgeClass)
            throws Exception {
        validateBridgeable(bridgeClass.getName());
        return bridgeClass
                .cast(loadClass(implClass.getName()).getDeclaredConstructor().newInstance());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (loadWithParentClassLoader(name)) {
            return super.loadClass(name, resolve);
        }
        Class<?> c = classes.get(name);
        if (c != null) {
            return c;
        }
        c = findClass(name);
        classes.put(name, c);
        return c;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (Class<?> bridgeClass : bridgeClasses) {
            if (bridgeClass.getName().equals(name)) {
                return bridgeClass;
            }
        }
        byte[] bytes = manualClasses.get(name);
        CodeSource codeSource = null;
        if (bytes == null) {
            String resourceName = ClassNames.toInternalName(name) + ".class";
            URL url = getResource(resourceName);
            if (url == null) {
                throw new ClassNotFoundException(name);
            }
            try {
                bytes = Resources.toByteArray(url);
            } catch (IOException e) {
                throw new ClassNotFoundException("Error loading class", e);
            }
            String path = url.getPath();
            if (url.getProtocol().equals("jar")) {
                int index = path.indexOf("!/");
                File jarFile = new File(path.substring(5, index));
                try {
                    codeSource = new CodeSource(jarFile.toURI().toURL(), (CodeSigner[]) null);
                } catch (MalformedURLException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        return weaveAndDefineClass(name, bytes, codeSource);
    }

    @Override
    protected @Nullable Package getPackage(String name) {
        return packages.get(name);
    }

    public Class<?> weaveAndDefineClass(String name, byte[] bytes,
            @Nullable CodeSource codeSource) {
        byte[] wovenBytes = weaveClass(name, bytes);
        String packageName = Reflection.getPackageName(name);
        if (!packages.containsKey(packageName)) {
            Method getDefinedPackageMethod;
            try {
                getDefinedPackageMethod = getClass().getMethod("getDefinedPackage", String.class);
            } catch (NoSuchMethodException e) {
                getDefinedPackageMethod = null;
            }
            Package pkg;
            if (getDefinedPackageMethod == null) {
                pkg = definePackage(packageName, null, null, null, null, null, null, null);
            } else {
                // Java 9
                try {
                    pkg = (Package) getDefinedPackageMethod.invoke(this, packageName);
                    if (pkg == null) {
                        pkg = definePackage(packageName, null, null, null, null, null, null, null);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
            packages.put(packageName, pkg);
        }
        if (codeSource == null) {
            return super.defineClass(name, wovenBytes, 0, wovenBytes.length);
        } else {
            ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
            return super.defineClass(name, wovenBytes, 0, wovenBytes.length, protectionDomain);
        }
    }

    private byte[] weaveClass(String name, byte[] bytes) throws ClassFormatError {
        if (weaver == null || inWeaving.get()) {
            return bytes;
        } else {
            // don't do recursive weaving (i.e. don't weave any of the classes which are performing
            // the weaving itself)
            inWeaving.set(true);
            try {
                byte[] wovenBytes =
                        weaver.weave(bytes, ClassNames.toInternalName(name), null, this);
                if (wovenBytes == null) {
                    return bytes;
                } else {
                    logger.debug("findClass(): transformed {}", name);
                    return wovenBytes;
                }
            } finally {
                inWeaving.remove();
            }
        }
    }

    private void validateBridgeable(String name) {
        if (isInBootstrapClassLoader(name)) {
            return;
        }
        for (Class<?> bridgeClass : bridgeClasses) {
            if (bridgeClass.getName().equals(name)) {
                return;
            }
        }
        throw new IllegalStateException("Class '" + name + "' is not bridgeable");
    }

    public static boolean loadWithParentClassLoader(String name) {
        if (isInBootstrapClassLoader(name)) {
            return true;
        }
        if (name.startsWith("java.")) {
            // in Java 9, some java packages are no longer in the bootstrap class loader, but still
            // need to load with parent class loader in order to avoid SecurityException "Prohibited
            // package name"
            return true;
        }
        // this is needed to prevent these thread locals retaining the IsolatedWeavingClassLoader
        // and causing PermGen OOM during maven test
        if (name.equals(FastThreadLocal.class.getName())
                || name.equals(FastThreadLocal.class.getName() + "$Holder")
                || name.equals(ThreadContextThreadLocal.class.getName())
                || name.equals(ThreadContextThreadLocal.class.getName() + "$Holder")) {
            return true;
        }
        if (name.equals(Glowroot.class.getName())) {
            return false;
        }
        // these are the classes that plugins depend on, either directly (api classes) or indirectly
        // (weaving)
        if (name.startsWith("org.glowroot.agent.api.")
                || name.startsWith("org.glowroot.agent.plugin.api.")
                || name.startsWith("org.glowroot.agent.weaving.GeneratedAdvice")
                || name.startsWith("org.glowroot.agent.weaving.GeneratedMethodMeta")
                || name.equals(OptionalThreadContextImpl.class.getName())
                || name.equals(ServiceRegistryImpl.class.getName())
                || name.equals(ThreadContextImpl.class.getName())
                || name.equals(ThreadContextPlus.class.getName())
                || name.equals(TransactionRegistry.class.getName())
                || name.equals(TransactionRegistry.TransactionRegistryHolder.class.getName())
                || name.equals(TransactionServiceImpl.class.getName())
                || name.equals(TransactionServiceImpl.TransactionServiceHolder.class.getName())) {
            return true;
        }
        return false;
    }

    private static boolean isInBootstrapClassLoader(String name) {
        try {
            Class<?> cls = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return cls.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }
}
