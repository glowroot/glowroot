/*
 * Copyright 2011-2014 the original author or authors.
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

import java.io.IOException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.reflect.Reflection;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The placement of this code in the main Glowroot code base (and not inside of the tests folder) is
 * not ideal, but the alternative is to create a separate artifact (or at least classifier) for this
 * small amount of code, which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@OnlyUsedByTests
@ThreadSafe
public class IsolatedWeavingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedWeavingClassLoader.class);

    // bridge classes can be either interfaces or base classes
    private final ImmutableList<Class<?>> bridgeClasses;
    private final ImmutableList<String> excludePackages;
    private final Weaver weaver;
    // guarded by 'this'
    private final Map<String, Class<?>> classes = Maps.newConcurrentMap();

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> inWeaving = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    public static Builder builder() {
        return new Builder();
    }

    private IsolatedWeavingClassLoader(List<Advice> advisors, List<MixinType> mixinTypes,
            WeavingTimerService weavingTimerService, List<Class<?>> bridgeClasses,
            List<String> excludePackages, boolean metricWrapperMethods) {
        super(IsolatedWeavingClassLoader.class.getClassLoader());
        this.bridgeClasses = ImmutableList.copyOf(bridgeClasses);
        this.excludePackages = ImmutableList.copyOf(excludePackages);
        Supplier<ImmutableList<Advice>> advisorsSupplier =
                Suppliers.ofInstance(ImmutableList.copyOf(advisors));
        Weaver weaver = new Weaver(advisorsSupplier, mixinTypes,
                new AnalyzedWorld(advisorsSupplier, mixinTypes, null), weavingTimerService,
                metricWrapperMethods);
        this.weaver = weaver;
    }

    public <S extends /*@Nullable*/Object, T extends S> S newInstance(Class<T> implClass,
            Class<S> bridgeClass) throws BridgeInstantiationException {
        if (!isBridgeable(bridgeClass.getName())) {
            throw new BridgeInstantiationException("Class '" + bridgeClass + "' is not bridgeable");
        }
        try {
            return bridgeClass.cast(loadClass(implClass.getName()).newInstance());
        } catch (ClassNotFoundException e) {
            throw new BridgeInstantiationException(e);
        } catch (InstantiationException e) {
            throw new BridgeInstantiationException(e);
        } catch (IllegalAccessException e) {
            throw new BridgeInstantiationException(e);
        }
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
        String resourceName = ClassNames.toInternalName(name) + ".class";
        URL url = getResource(resourceName);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try {
            bytes = Resources.toByteArray(url);
        } catch (IOException e) {
            throw new ClassNotFoundException("Error loading class", e);
        }
        bytes = weaveClass(name, bytes);
        String packageName = Reflection.getPackageName(name);
        if (getPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
        return super.defineClass(name, bytes, 0, bytes.length);
    }

    private byte[] weaveClass(String name, byte[] bytes) throws ClassFormatError {
        if (inWeaving.get()) {
            return bytes;
        } else {
            // don't do recursive weaving (i.e. don't weave any of the classes which are performing
            // the weaving itself)
            inWeaving.set(true);
            try {
                byte[] wovenBytes = weaver.weave(bytes, name, null, this);
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
        if (name.equals(AdviceFlowOuterHolder.class.getName())
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
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("serial")
    public static class BridgeInstantiationException extends Exception {
        private BridgeInstantiationException(Exception cause) {
            super(cause);
        }
        private BridgeInstantiationException(String message) {
            super(message);
        }
    }

    public static class Builder {

        private List<MixinType> mixinTypes = Lists.newArrayList();
        private List<Advice> advisors = Lists.newArrayList();
        @MonotonicNonNull
        private WeavingTimerService weavingTimerService;
        private boolean metricWrapperMethods = true;
        private final List<Class<?>> bridgeClasses = Lists.newArrayList();
        private final List<String> excludePackages = Lists.newArrayList();

        private Builder() {}

        public void setMixinTypes(List<MixinType> mixinTypes) {
            this.mixinTypes = mixinTypes;
        }

        public void setAdvisors(List<Advice> advisors) {
            this.advisors = advisors;
        }

        @EnsuresNonNull("weavingTimerService")
        public void setWeavingTimerService(WeavingTimerService weavingTimerService) {
            this.weavingTimerService = weavingTimerService;
        }

        public void setMetricWrapperMethods(boolean metricWrapperMethods) {
            this.metricWrapperMethods = metricWrapperMethods;
        }

        public void addBridgeClasses(Class<?>... bridgeClasses) {
            this.bridgeClasses.addAll(Arrays.asList(bridgeClasses));
        }

        public void addExcludePackages(String... excludePackages) {
            this.excludePackages.addAll(Arrays.asList(excludePackages));
        }

        @RequiresNonNull("weavingTimerService")
        public IsolatedWeavingClassLoader build() {
            return AccessController.doPrivileged(
                    new PrivilegedAction<IsolatedWeavingClassLoader>() {
                        @Override
                        public IsolatedWeavingClassLoader run() {
                            // weavingTimerService is non-null when outer method is called, and it
                            // is
                            // @MonotonicNonNull, so it must be non-null here
                            checkNotNull(weavingTimerService);
                            return new IsolatedWeavingClassLoader(advisors, mixinTypes,
                                    weavingTimerService, bridgeClasses, excludePackages,
                                    metricWrapperMethods);
                        }
                    });
        }
    }
}
