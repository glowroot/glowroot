/**
 * Copyright 2011-2012 the original author or authors.
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
import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Timer;
import org.informantproject.api.weaving.Mixin;
import org.informantproject.core.util.UnitTests.OnlyUsedByTests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@OnlyUsedByTests
@ThreadSafe
public class IsolatedWeavingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedWeavingClassLoader.class);

    private final ImmutableList<Mixin> mixins;
    private final ImmutableList<Advice> advisors;
    private final List<Class<?>> bridgeClasses;
    // guarded by 'this'
    private final Map<String, Class<?>> classes = Maps.newConcurrentMap();
    @Nullable
    private volatile Weaver weaver;

    private final ThreadLocal<Boolean> inWeaving = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    // bridge classes can be either interfaces or base classes
    public IsolatedWeavingClassLoader(List<Mixin> mixins, List<Advice> advisors,
            Class<?>... bridgeClasses) {

        super(IsolatedWeavingClassLoader.class.getClassLoader());
        this.mixins = ImmutableList.copyOf(mixins);
        this.advisors = ImmutableList.copyOf(advisors);
        this.bridgeClasses = ImmutableList.copyOf(Lists.asList(WeavingMetric.class,
                Timer.class, bridgeClasses));
    }

    public <S, T extends S> S newInstance(Class<T> implClass, Class<S> bridgeClass)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {

        if (isBridgeable(bridgeClass.getName())) {
            return bridgeClass.cast(loadClass(implClass.getName()).newInstance());
        } else {
            throw new IllegalStateException("Class '" + bridgeClass + "' is not bridgeable");
        }
    }

    // TODO this api is a bit awkward requiring construction and then initialization
    public void initWeaver(WeavingMetric weavingMetric) {
        weaver = new Weaver(mixins, advisors, this, new ParsedTypeCache(), weavingMetric);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {

        if (isInBootstrapClassLoader(name)) {
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
        // don't weave informant classes, included shaded classes like h2 jdbc driver
        if (name.startsWith("org/informantproject/core/")
                || name.startsWith("org/informantproject/local/")
                || name.startsWith("org/informantproject/shaded/")) {
            return super.findClass(name);
        }
        String resourceName = name.replace('.', '/') + ".class";
        URL url = getResource(resourceName);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try {
            bytes = ByteStreams.toByteArray(Resources.newInputStreamSupplier(url));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // don't weave before the weaver has been set via initWeaver()
        // also don't do recursive weaving (i.e. don't weave any of the classes which are performing
        // the weaving itself)
        if (weaver != null && !inWeaving.get()) {
            inWeaving.set(true);
            try {
                byte[] originalBytes = bytes;
                bytes = weaver.weave(originalBytes);
                if (bytes != originalBytes) {
                    logger.debug("findClass(): transformed {}", name);
                }
            } finally {
                inWeaving.remove();
            }
        }
        if (name.indexOf('.') != -1) {
            String packageName = name.substring(0, name.lastIndexOf('.'));
            if (getPackage(packageName) == null) {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        }
        return super.defineClass(name, bytes, 0, bytes.length);
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

    private static boolean isInBootstrapClassLoader(String name) {
        try {
            Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return c.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
