/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.weaving;

import java.io.IOException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Map;

import checkers.igj.quals.ReadOnly;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.reflect.Reflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;

/**
 * The placement of this code in the main Informant code base (and not inside of the tests folder)
 * is not ideal, but the alternative is to create a separate artifact (or at least classifier) for
 * this small amount of code, which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@OnlyUsedByTests
@ThreadSafe
public class IsolatedWeavingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedWeavingClassLoader.class);

    private static final Supplier<ImmutableList<Advice>> SUPPLIER_OF_NONE =
            new Supplier<ImmutableList<Advice>>() {
                public ImmutableList<Advice> get() {
                    return ImmutableList.of();
                }
            };

    private final Weaver weaver;
    // bridge classes can be either interfaces or base classes
    private final ImmutableList<Class<?>> bridgeClasses;
    private final ImmutableList<String> excludePackages;
    // guarded by 'this'
    private final Map<String, Class<?>> classes = Maps.newConcurrentMap();

    private final ThreadLocal<Boolean> inWeaving = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public static Builder builder() {
        return new Builder();
    }

    private IsolatedWeavingClassLoader(ImmutableList<MixinType> mixinTypes,
            ImmutableList<Advice> advisors, ImmutableList<Class<?>> bridgeClasses,
            ImmutableList<String> excludePackages, WeavingMetricName weavingMetricName) {
        super(IsolatedWeavingClassLoader.class.getClassLoader());
        weaver = new Weaver(mixinTypes, advisors, SUPPLIER_OF_NONE, this, new ParsedTypeCache(),
                weavingMetricName);
        this.bridgeClasses = bridgeClasses;
        this.excludePackages = excludePackages;
    }

    public <S, T extends S> S newInstance(Class<T> implClass, Class<S> bridgeClass)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {

        if (isBridgeable(bridgeClass.getName())) {
            return bridgeClass.cast(loadClass(implClass.getName()).newInstance());
        } else {
            throw new IllegalStateException("Class '" + bridgeClass + "' is not bridgeable");
        }
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {

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
        String resourceName = name.replace('.', '/') + ".class";
        URL url = getResource(resourceName);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try {
            bytes = Resources.toByteArray(url);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return weaveClass(name, bytes);
    }

    private Class<?> weaveClass(String name, byte[] bytes) throws ClassFormatError {
        byte[] wovenBytes = bytes;
        if (!inWeaving.get()) {
            // don't do recursive weaving (i.e. don't weave any of the classes which are performing
            // the weaving itself)
            inWeaving.set(true);
            try {
                wovenBytes = weaver.weave(bytes, name);
                if (wovenBytes != bytes) {
                    logger.debug("findClass(): transformed {}", name);
                }
            } finally {
                inWeaving.remove();
            }
        }
        String packageName = Reflection.getPackageName(name);
        if (getPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
        return super.defineClass(name, wovenBytes, 0, wovenBytes.length);
    }

    private <S> boolean isBridgeable(String name) {
        if (isInBootstrapClassLoader(name)) {
            return true;
        }
        for (Class<?> bridgeClass : bridgeClasses) {
            if (bridgeClass.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean loadWithParentClassLoader(String name) {
        if (isInBootstrapClassLoader(name)) {
            return true;
        }
        for (String excludePackage : excludePackages) {
            if (name.startsWith(excludePackage + ".")) {
                return true;
            }
        }
        if (name.matches("io.informant.weaving.GeneratedAdviceFlow[0-9]+")
                || name.equals(AdviceFlowOuterHolder.class.getName())
                || name.equals(AdviceFlowOuterHolder.AdviceFlowHolder.class.getName())) {
            return true;
        }
        return false;
    }

    private static boolean isInBootstrapClassLoader(String name) {
        try {
            Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return c.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static class Builder {

        private ImmutableList<MixinType> mixinTypes = ImmutableList.of();
        private ImmutableList<Advice> advisors = ImmutableList.of();
        private final ImmutableList.Builder<Class<?>> bridgeClasses = ImmutableList.builder();
        private final ImmutableList.Builder<String> excludePackages = ImmutableList.builder();
        private WeavingMetricName weavingMetricName = NopWeavingMetricName.INSTANCE;

        private Builder() {}

        public void setMixinTypes(@ReadOnly List<MixinType> mixinTypes) {
            this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        }

        public void setAdvisors(@ReadOnly Iterable<Advice> advisors) {
            this.advisors = ImmutableList.copyOf(advisors);
        }

        public void addBridgeClasses(Class<?>... bridgeClasses) {
            this.bridgeClasses.add(bridgeClasses);
        }

        public void addExcludePackages(String... excludePackages) {
            this.excludePackages.add(excludePackages);
        }

        public void weavingMetric(WeavingMetricName weavingMetricName) {
            this.weavingMetricName = weavingMetricName;
        }

        public IsolatedWeavingClassLoader build() {
            return AccessController.doPrivileged(
                    new PrivilegedAction<IsolatedWeavingClassLoader>() {
                        public IsolatedWeavingClassLoader run() {
                            return new IsolatedWeavingClassLoader(mixinTypes, advisors,
                                    bridgeClasses.build(), excludePackages.build(),
                                    weavingMetricName);
                        }
                    });
        }
    }
}
