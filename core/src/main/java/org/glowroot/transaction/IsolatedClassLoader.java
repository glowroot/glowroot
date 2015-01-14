/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.transaction;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

import com.google.common.io.ByteStreams;

class IsolatedClassLoader extends URLClassLoader {

    IsolatedClassLoader(URL[] urls) {
        super(urls);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (useBootstrapClassLoader(name)) {
            return super.findClass(name);
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
        }
        int lastIndexOf = name.lastIndexOf('.');
        if (lastIndexOf != -1) {
            String packageName = name.substring(0, lastIndexOf);
            createPackageIfNecessary(packageName);
        }
        return defineClass(name, b, 0, b.length);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        if (useBootstrapClassLoader(name)) {
            return super.loadClass(name, resolve);
        }
        return findClass(name);
    }

    private boolean useBootstrapClassLoader(String name) {
        return name.startsWith("java.") || name.startsWith("sun.")
                || name.startsWith("javax.management.")
                || name.startsWith("org.glowroot.api.");
    }

    private void createPackageIfNecessary(String packageName) {
        if (getPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null);
        }
    }
}
