/*
 * Copyright 2013-2014 the original author or authors.
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;

import static com.google.common.base.Preconditions.checkNotNull;

public class ClassLoaders {

    private static final Logger logger = LoggerFactory.getLogger(ClassLoaders.class);

    private ClassLoaders() {}

    public static void defineClassesInBootstrapClassLoader(
            Collection<LazyDefinedClass> lazyDefinedClasses, Instrumentation instrumentation,
            File generatedJarFile) {
        try {
            JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(generatedJarFile));
            generate(lazyDefinedClasses, jarOut);
            jarOut.close();
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(generatedJarFile));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static void defineClassesInClassLoader(Collection<LazyDefinedClass> lazyDefinedClasses,
            ClassLoader loader) {
        try {
            for (LazyDefinedClass lazyDefinedClass : lazyDefinedClasses) {
                defineClass(lazyDefinedClass, loader);
            }
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static Class<?> defineClass(LazyDefinedClass lazyDefinedClass, ClassLoader loader)
            throws ReflectiveException {
        for (LazyDefinedClass dependency : lazyDefinedClass.getDependencies()) {
            defineClass(dependency, loader);
        }
        return defineClass(lazyDefinedClass.getType().getClassName(), lazyDefinedClass.getBytes(),
                loader);
    }

    public static Class<?> defineClass(String name, byte[] bytes, ClassLoader loader)
            throws ReflectiveException {
        Method defineClassMethod = Reflections.getDeclaredMethod(ClassLoader.class, "defineClass",
                String.class, byte[].class, int.class, int.class);
        Class<?> definedClass = (Class<?>) Reflections.invoke(defineClassMethod, loader, name,
                bytes, 0, bytes.length);
        checkNotNull(definedClass);
        return definedClass;
    }

    public static void cleanPreviouslyGeneratedJars(File generatedJarDir,
            String deleteJarsWithPrefix) throws IOException {
        if (generatedJarDir.exists() && generatedJarDir.isFile()) {
            if (!generatedJarDir.delete()) {
                throw new IOException("Could not delete file: "
                        + generatedJarDir.getAbsolutePath());
            }
        }
        if (!generatedJarDir.exists() && !generatedJarDir.mkdirs()) {
            throw new IOException("Could not create directory: "
                    + generatedJarDir.getAbsolutePath());
        }
        File[] files = generatedJarDir.listFiles();
        if (files == null) {
            // strangely, listFiles() returns null if an I/O error occurs
            throw new IOException("Could not get listing for directory: "
                    + generatedJarDir.getAbsolutePath());
        }
        for (File file : files) {
            if (file.getName().startsWith(deleteJarsWithPrefix)) {
                if (!file.delete()) {
                    throw new IOException("Could not delete file: " + file.getAbsolutePath());
                }
            }
        }
    }

    private static void generate(Collection<LazyDefinedClass> lazyDefinedClasses,
            JarOutputStream jarOut) throws IOException {
        for (LazyDefinedClass lazyDefinedClass : lazyDefinedClasses) {
            JarEntry jarEntry =
                    new JarEntry(lazyDefinedClass.getType().getInternalName() + ".class");
            jarOut.putNextEntry(jarEntry);
            jarOut.write(lazyDefinedClass.getBytes());
            jarOut.closeEntry();
            generate(lazyDefinedClass.getDependencies(), jarOut);
        }
    }
}
