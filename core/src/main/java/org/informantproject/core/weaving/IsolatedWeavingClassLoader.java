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
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.informantproject.api.weaving.Mixin;

import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

/**
 * Only used by tests.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class IsolatedWeavingClassLoader extends ClassLoader {

    private final Class<?>[] bridgeClasses;
    private final Weaver weaver;
    // guarded by 'this'
    private final Map<String, Class<?>> classes = Maps.newHashMap();

    // bridge classes can be either interfaces or base classes
    public IsolatedWeavingClassLoader(List<Mixin> mixins, List<Advice> advisors,
            Class<?>... bridgeClasses) {

        super(IsolatedWeavingClassLoader.class.getClassLoader());
        this.bridgeClasses = bridgeClasses;
        weaver = new Weaver(mixins, advisors, this);
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
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (isInBootClassLoader(name)) {
            return super.findClass(name);
        }
        for (Class<?> bridgeClass : bridgeClasses) {
            if (bridgeClass.getName().equals(name)) {
                return bridgeClass;
            }
        }
        String resourceName = name.replace('.', '/') + ".class";
        InputStream input = getResourceAsStream(resourceName);
        if (input == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] b;
        try {
            b = ByteStreams.toByteArray(input);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            Closeables.closeQuietly(input);
        }
        b = weaver.weave(b);
        if (name.indexOf('.') != -1) {
            String packageName = name.substring(0, name.lastIndexOf('.'));
            if (getPackage(packageName) == null) {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        }
        return super.defineClass(name, b, 0, b.length);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {

        if (isInBootClassLoader(name)) {
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

    private <S> boolean isBridgeable(String name) {
        if (isInBootClassLoader(name)) {
            return true;
        }
        for (Class<?> bridgeClass : bridgeClasses) {
            if (bridgeClass.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInBootClassLoader(String name) {
        try {
            Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return c.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
