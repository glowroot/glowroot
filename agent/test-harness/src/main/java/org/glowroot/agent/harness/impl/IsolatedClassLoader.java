/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.harness.impl;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.reflect.Reflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.weaving.ClassNames;
import org.glowroot.agent.plugin.api.Agent;

class IsolatedClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedClassLoader.class);

    // bridge classes can be either interfaces or base classes
    private final ImmutableList<Class<?>> bridgeClasses;
    private final ImmutableList<String> excludePackages;
    private final Map<String, Class<?>> classes = Maps.newConcurrentMap();

    IsolatedClassLoader(List<Class<?>> bridgeClasses, List<String> excludePackages) {
        super(IsolatedClassLoader.class.getClassLoader());
        this.bridgeClasses = ImmutableList.<Class<?>>builder()
                .addAll(bridgeClasses)
                .add(IsolatedClassLoader.class)
                .build();
        this.excludePackages = ImmutableList.copyOf(excludePackages);
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

    private Class<?> weaveAndDefineClass(String name, byte[] bytes) {
        String packageName = Reflection.getPackageName(name);
        if (getPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
        return super.defineClass(name, bytes, 0, bytes.length);
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
