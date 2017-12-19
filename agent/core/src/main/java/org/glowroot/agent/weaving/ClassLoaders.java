/*
 * Copyright 2013-2017 the original author or authors.
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import org.immutables.value.Value;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

class ClassLoaders {

    private static final Logger logger = LoggerFactory.getLogger(ClassLoaders.class);

    private ClassLoaders() {}

    static void defineClassesInBootstrapClassLoader(Collection<LazyDefinedClass> lazyDefinedClasses,
            Instrumentation instrumentation, File generatedJarFile) throws IOException {
        Closer closer = Closer.create();
        try {
            FileOutputStream out = closer.register(new FileOutputStream(generatedJarFile));
            JarOutputStream jarOut = closer.register(new JarOutputStream(out));
            generate(lazyDefinedClasses, jarOut);
        } catch (Throwable t) {
            closer.rethrow(t);
        } finally {
            closer.close();
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(generatedJarFile));
        // appendToBootstrapClassLoaderSearch() line above does not add to the bootstrap resource
        // search path, only to the bootstrap class search path (this is different from
        // appendToSystemClassLoaderSearch() which adds to both the system resource search path and
        // the system class search path)
        //
        // adding the generated jar file to the bootstrap resource search path is probably needed
        // more generally, but it is at least needed to support jboss 4.2.0 - 4.2.3 because
        // org.jboss.mx.loading.LoadMgr3.beginLoadTask() checks that the class loader has the class
        // as a resource before loading it, so without adding the generated jar file to the
        // bootstrap resource search path, jboss ends up throwing ClassNotFoundException for the
        // glowroot generated classes that have been added to the bootstrap class loader search path
        // (see issue #101 for more info on this particular jboss issue)
        appendToBootstrapResourcePath(generatedJarFile);
    }

    static void defineClassesInClassLoader(Collection<LazyDefinedClass> lazyDefinedClasses,
            ClassLoader loader) throws Exception {
        for (LazyDefinedClass lazyDefinedClass : lazyDefinedClasses) {
            defineClass(lazyDefinedClass, loader);
        }
    }

    static Class<?> defineClass(LazyDefinedClass lazyDefinedClass, ClassLoader loader)
            throws Exception {
        for (LazyDefinedClass dependency : lazyDefinedClass.dependencies()) {
            defineClass(dependency, loader);
        }
        return defineClass(lazyDefinedClass.type().getClassName(), lazyDefinedClass.bytes(),
                loader);
    }

    static Class<?> defineClass(String name, byte[] bytes, ClassLoader loader) throws Exception {
        Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class,
                byte[].class, int.class, int.class);
        defineClassMethod.setAccessible(true);
        Class<?> definedClass =
                (Class<?>) defineClassMethod.invoke(loader, name, bytes, 0, bytes.length);
        checkNotNull(definedClass);
        return definedClass;
    }

    static void createDirectoryOrCleanPreviousContentsWithPrefix(File dir, String prefix)
            throws IOException {
        deleteIfRegularFile(dir);
        if (dir.exists()) {
            deleteFilesWithPrefix(dir, prefix);
        } else {
            createDirectory(dir);
        }
    }

    private static void generate(Collection<LazyDefinedClass> lazyDefinedClasses,
            JarOutputStream jarOut) throws IOException {
        for (LazyDefinedClass lazyDefinedClass : lazyDefinedClasses) {
            JarEntry jarEntry = new JarEntry(lazyDefinedClass.type().getInternalName() + ".class");
            jarOut.putNextEntry(jarEntry);
            jarOut.write(lazyDefinedClass.bytes());
            jarOut.closeEntry();
            generate(lazyDefinedClass.dependencies(), jarOut);
        }
    }

    private static void deleteIfRegularFile(File file) throws IOException {
        if (file.isFile() && !file.delete()) {
            throw new IOException("Could not delete file: " + file.getAbsolutePath());
        }
    }

    private static void deleteFilesWithPrefix(File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            // strangely, listFiles() returns null if an I/O error occurs
            throw new IOException("Could not get listing for directory: " + dir.getAbsolutePath());
        }
        for (File file : files) {
            if (file.getName().startsWith(prefix) && !file.delete()) {
                throw new IOException("Could not delete file: " + file.getAbsolutePath());
            }
        }
    }

    private static void createDirectory(File dir) throws IOException {
        if (!dir.mkdirs()) {
            throw new IOException("Could not create directory: " + dir.getAbsolutePath());
        }
    }

    private static void appendToBootstrapResourcePath(File generatedJarFile) {
        try {
            Class<?> launcherClass = Class.forName("sun.misc.Launcher", false, null);
            Method getBootstrapClassPathMethod = launcherClass.getMethod("getBootstrapClassPath");
            Class<?> urlClassPathClass = Class.forName("sun.misc.URLClassPath", false, null);
            Method addUrlMethod = urlClassPathClass.getMethod("addURL", URL.class);
            Object urlClassPath = getBootstrapClassPathMethod.invoke(null);
            addUrlMethod.invoke(urlClassPath, generatedJarFile.toURI().toURL());
        } catch (Exception e) {
            // NOTE sun.misc.Launcher no longer exists in Java 9
            logger.debug(e.getMessage(), e);
        }
    }

    @Value.Immutable
    public interface LazyDefinedClass {
        Type type();
        byte[] bytes();
        ImmutableList<LazyDefinedClass> dependencies();
    }
}
