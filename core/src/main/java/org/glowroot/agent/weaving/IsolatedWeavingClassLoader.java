/*
 * Copyright 2011-2015 the original author or authors.
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

import java.io.IOException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

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

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.plugin.api.Agent;

import static com.google.common.base.Preconditions.checkNotNull;

// the placement of this code in the main Glowroot code base (and not inside of the tests folder) is
// not ideal, but the alternative is to create a separate artifact (or at least classifier) for this
// small amount of code, which also seems to be not ideal
@OnlyUsedByTests
public class IsolatedWeavingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedWeavingClassLoader.class);

    // bridge classes can be either interfaces or base classes
    private final ImmutableList<Class<?>> bridgeClasses;
    private final ImmutableList<String> excludePackages;
    private final Weaver weaver;
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

    private IsolatedWeavingClassLoader(@Nullable ClassLoader parentClassLoader,
            List<Advice> advisors, List<ShimType> shimTypes, List<MixinType> mixinTypes,
            WeavingTimerService weavingTimerService, List<Class<?>> bridgeClasses,
            List<String> excludePackages, boolean timerWrapperMethods) {
        super(parentClassLoader);
        this.bridgeClasses = ImmutableList.<Class<?>>builder()
                .addAll(bridgeClasses)
                .add(IsolatedWeavingClassLoader.class)
                .build();
        this.excludePackages = ImmutableList.copyOf(excludePackages);
        Supplier<List<Advice>> advisorsSupplier =
                Suppliers.<List<Advice>>ofInstance(ImmutableList.copyOf(advisors));
        AnalyzedWorld analyzedWorld =
                new AnalyzedWorld(advisorsSupplier, shimTypes, mixinTypes, null);
        this.weaver = new Weaver(advisorsSupplier, shimTypes, mixinTypes, analyzedWorld,
                weavingTimerService, timerWrapperMethods);
    }

    public <S, T extends S> S newInstance(Class<T> implClass, Class<S> bridgeClass)
            throws Exception {
        validateBridgeable(bridgeClass.getName());
        return bridgeClass.cast(loadClass(implClass.getName()).newInstance());
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
        return weaveAndDefineClass(name, bytes);
    }

    public Class<?> weaveAndDefineClass(String name, byte[] bytes) {
        byte[] wovenBytes = weaveClass(name, bytes);
        String packageName = Reflection.getPackageName(name);
        if (getPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
        return super.defineClass(name, wovenBytes, 0, wovenBytes.length);
    }

    private byte[] weaveClass(String name, byte[] bytes) throws ClassFormatError {
        if (inWeaving.get()) {
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

    private boolean loadWithParentClassLoader(String name) {
        if (name.startsWith(Agent.class.getName())) {
            return false;
        }
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
            Class<?> cls = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return cls.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }

    public static class Builder {

        private @Nullable ClassLoader parentClassLoader = Builder.class.getClassLoader();
        private List<ShimType> shimTypes = Lists.newArrayList();
        private List<MixinType> mixinTypes = Lists.newArrayList();
        private List<Advice> advisors = Lists.newArrayList();
        private @MonotonicNonNull WeavingTimerService weavingTimerService;
        private boolean timerWrapperMethods = true;
        private final List<Class<?>> bridgeClasses = Lists.newArrayList();
        private final List<String> excludePackages = Lists.newArrayList();

        private Builder() {}

        public void setParentClassLoader(ClassLoader parentClassLoader) {
            this.parentClassLoader = parentClassLoader;
        }

        public void setShimTypes(List<ShimType> shimTypes) {
            this.shimTypes = shimTypes;
        }

        public void setMixinTypes(List<MixinType> mixinTypes) {
            this.mixinTypes = mixinTypes;
        }

        public void setAdvisors(List<Advice> advisors) {
            this.advisors = advisors;
        }

        @EnsuresNonNull("#1")
        public void setWeavingTimerService(WeavingTimerService weavingTimerService) {
            this.weavingTimerService = weavingTimerService;
        }

        public void setTimerWrapperMethods(boolean timerWrapperMethods) {
            this.timerWrapperMethods = timerWrapperMethods;
        }

        public void addBridgeClasses(List<Class<?>> bridgeClasses) {
            this.bridgeClasses.addAll(bridgeClasses);
        }

        void addBridgeClasses(Class<?>... bridgeClasses) {
            this.bridgeClasses.addAll(Arrays.asList(bridgeClasses));
        }

        public void addExcludePackages(List<String> excludePackages) {
            this.excludePackages.addAll(excludePackages);
        }

        @RequiresNonNull("weavingTimerService")
        public IsolatedWeavingClassLoader build() {
            return AccessController
                    .doPrivileged(new PrivilegedAction<IsolatedWeavingClassLoader>() {
                        @Override
                        public IsolatedWeavingClassLoader run() {
                            // weavingTimerService is non-null when outer method is called, and it
                            // is
                            // @MonotonicNonNull, so it must be non-null here
                            checkNotNull(weavingTimerService);
                            return new IsolatedWeavingClassLoader(parentClassLoader, advisors,
                                    shimTypes, mixinTypes, weavingTimerService, bridgeClasses,
                                    excludePackages, timerWrapperMethods);
                        }
                    });
        }
    }
}
