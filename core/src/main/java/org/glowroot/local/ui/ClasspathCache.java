/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ClassNames;
import org.glowroot.common.Reflections;
import org.glowroot.weaving.AnalyzedWorld;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM5;

// TODO remove items from classpathLocations and classNameLocations when class loaders are no longer
// present, e.g. in wildfly after undeploying an application
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;

    @GuardedBy("this")
    private final Set<File> classpathLocations = Sets.newHashSet();

    // using ImmutableMultimap because it is very space efficient
    // this is not updated often so trading space efficiency for copying the entire map on update
    @GuardedBy("this")
    private ImmutableMultimap<String, File> classNameLocations = ImmutableMultimap.of();

    ClasspathCache(AnalyzedWorld analyzedWorld, @Nullable Instrumentation instrumentation) {
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
    }

    // using synchronization instead of concurrent structures in this cache to conserve memory
    synchronized ImmutableList<String> getMatchingClassNames(String partialClassName, int limit) {
        // update cache before proceeding
        updateCache();
        PartialClassNameMatcher matcher = new PartialClassNameMatcher(partialClassName);
        Set<String> fullMatchingClassNames = Sets.newLinkedHashSet();
        Set<String> matchingClassNames = Sets.newLinkedHashSet();
        // also check loaded classes, e.g. for groovy classes
        Iterator<String> i = classNameLocations.keySet().iterator();
        if (instrumentation != null) {
            List<String> loadedClassNames = Lists.newArrayList();
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                loadedClassNames.add(clazz.getName());
            }
            i = Iterators.concat(i, loadedClassNames.iterator());
        }
        while (i.hasNext()) {
            String className = i.next();
            String classNameUpper = className.toUpperCase(Locale.ENGLISH);
            boolean potentialFullMatch = matcher.isPotentialFullMatch(classNameUpper);
            if (matchingClassNames.size() == limit && !potentialFullMatch) {
                // once limit reached, only consider full matches
                continue;
            }
            if (fullMatchingClassNames.size() == limit) {
                break;
            }
            if (matcher.isPotentialMatch(classNameUpper)) {
                if (potentialFullMatch) {
                    fullMatchingClassNames.add(className);
                } else {
                    matchingClassNames.add(className);
                }
            }
        }
        return combineClassNamesWithLimit(fullMatchingClassNames, matchingClassNames, limit);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized ImmutableList<UiAnalyzedMethod> getAnalyzedMethods(String className) {
        // update cache before proceeding
        updateCache();
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        Collection<File> locations = classNameLocations.get(className);
        for (File location : locations) {
            try {
                analyzedMethods.addAll(getAnalyzedMethods(location, className));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        if (instrumentation != null) {
            // also check loaded classes, e.g. for groovy classes
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    analyzedMethods.addAll(getAnalyzedMethods(clazz));
                }
            }
        }
        return ImmutableList.copyOf(analyzedMethods);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized void updateCache() {
        Multimap<String, File> newClassNameLocations = HashMultimap.create();
        for (ClassLoader loader : getKnownClassLoaders()) {
            updateCache(loader, newClassNameLocations);
        }
        updateCacheWithBootstrapClasses(newClassNameLocations);
        if (!newClassNameLocations.isEmpty()) {
            Multimap<String, File> newMap =
                    TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, Ordering.natural());
            newMap.putAll(classNameLocations);
            newMap.putAll(newClassNameLocations);
            classNameLocations = ImmutableMultimap.copyOf(newMap);
        }
    }

    private ImmutableList<String> combineClassNamesWithLimit(Set<String> fullMatchingClassNames,
            Set<String> matchingClassNames, int limit) {
        if (fullMatchingClassNames.size() < limit) {
            int space = limit - fullMatchingClassNames.size();
            int numToAdd = Math.min(space, matchingClassNames.size());
            fullMatchingClassNames.addAll(
                    ImmutableList.copyOf(Iterables.limit(matchingClassNames, numToAdd)));
        }
        return ImmutableList.copyOf(fullMatchingClassNames);
    }

    private void updateCacheWithBootstrapClasses(Multimap<String, File> newClassNameLocations) {
        String bootClassPath = System.getProperty("sun.boot.class.path");
        if (bootClassPath == null) {
            return;
        }
        for (String path : Splitter.on(File.pathSeparatorChar).split(bootClassPath)) {
            File file = new File(path);
            if (!classpathLocations.contains(file)) {
                loadClassNames(file, newClassNameLocations);
                classpathLocations.add(file);
            }
        }
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(File location, String className)
            throws IOException {
        String name = className.replace('.', '/') + ".class";
        if (location.isDirectory()) {
            URI uri = new File(location, name).toURI();
            return getAnalyzedMethods(uri);
        } else if (location.exists() && location.getName().endsWith(".jar")) {
            String path = location.getPath();
            try {
                URI uri = new URI("jar", "file:" + path + "!/" + name, "");
                return getAnalyzedMethods(uri);
            } catch (URISyntaxException e) {
                logger.error(e.getMessage(), e);
            }
        }
        return ImmutableList.of();
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(URI uri) throws IOException {
        AnalyzingClassVisitor cv = new AnalyzingClassVisitor();
        byte[] bytes = Resources.toByteArray(uri.toURL());
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.getAnalyzedMethods();
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(Class<?> clazz) {
        List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isNative(method.getModifiers())) {
                // don't add synthetic or native methods to the analyzed model
                continue;
            }
            UiAnalyzedMethod.Builder builder = UiAnalyzedMethod.builder();
            builder.name(method.getName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
                // so using Type.getType().getClassName() instead
                builder.addParameterTypes(Type.getType(parameterType).getClassName());
            }
            // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
            // so using Type.getType().getClassName() instead
            builder.returnType(Type.getType(method.getReturnType()).getClassName());
            builder.modifiers(method.getModifiers());
            for (Class<?> exceptionType : method.getExceptionTypes()) {
                builder.addExceptions(exceptionType.getName());
            }
            analyzedMethods.add(builder.build());
        }
        return analyzedMethods;
    }

    private void updateCache(ClassLoader loader, Multimap<String, File> newClassNameLocations) {
        List<URL> urls = getURLs(loader);
        List<File> locations = Lists.newArrayList();
        for (URL url : urls) {
            if (url.getProtocol().equals("vfs")) {
                try {
                    locations.add(getFileFromJBossVfsURL(url, loader));
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            } else {
                try {
                    URI uri = url.toURI();
                    if (uri.getScheme().equals("file")) {
                        locations.add(new File(uri));
                    }
                } catch (URISyntaxException e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                }
            }
        }
        for (File location : locations) {
            if (!classpathLocations.contains(location)) {
                loadClassNames(location, newClassNameLocations);
                classpathLocations.add(location);
            }
        }
    }

    private List<URL> getURLs(ClassLoader loader) {
        if (loader instanceof URLClassLoader) {
            try {
                return Lists.newArrayList(((URLClassLoader) loader).getURLs());
            } catch (Exception e) {
                // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has
                // been called on the WebappClassLoader (this happens, for example, after a webapp
                // fails to load)
                //
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                return ImmutableList.of();
            }
        }
        // special case for jboss/wildfly
        try {
            return Collections.list(loader.getResources("/"));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private List<ClassLoader> getKnownClassLoaders() {
        List<ClassLoader> loaders = analyzedWorld.getClassLoaders();
        if (loaders.isEmpty()) {
            // this is needed for testing the UI outside of javaagent
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader == null) {
                return ImmutableList.of();
            } else {
                return ImmutableList.of(systemClassLoader);
            }
        }
        return loaders;
    }

    private static void loadClassNames(File file, Multimap<String, File> newClassNameLocations) {
        try {
            if (file.isDirectory()) {
                loadClassNamesFromDirectory(file, "", file, newClassNameLocations);
            } else if (file.exists() && file.getName().endsWith(".jar")) {
                loadClassNamesFromJarFile(file, newClassNameLocations);
            }
        } catch (IllegalArgumentException e) {
            // new File(URI) constructor can throw IllegalArgumentException
            logger.debug(e.getMessage(), e);
        } catch (IOException e) {
            logger.debug("error reading classes from file: {}", file, e);
        }
    }

    private static void loadClassNamesFromDirectory(File dir, String prefix,
            File location, Multimap<String, File> newClassNameLocations)
            throws MalformedURLException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class")) {
                String className = prefix + name.substring(0, name.lastIndexOf('.'));
                newClassNameLocations.put(className, location);
            } else if (file.isDirectory()) {
                loadClassNamesFromDirectory(file, prefix + name + ".", location,
                        newClassNameLocations);
            }
        }
    }

    private static void loadClassNamesFromJarFile(File jarFile,
            Multimap<String, File> newClassNameLocations) throws IOException {
        Closer closer = Closer.create();
        InputStream s = new FileInputStream(jarFile);
        JarInputStream jarIn = closer.register(new JarInputStream(s));
        try {
            loadClassNamesFromManifestClassPath(jarIn, jarFile, newClassNameLocations);
            loadClassNamesFromJarInputStream(jarIn, jarFile, newClassNameLocations);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static void loadClassNamesFromManifestClassPath(JarInputStream jarIn, File jarFile,
            Multimap<String, File> newClassNameLocations) {
        Manifest manifest = jarIn.getManifest();
        if (manifest == null) {
            return;
        }
        String classpath = manifest.getMainAttributes().getValue("Class-Path");
        if (classpath == null) {
            return;
        }
        URI baseUri = jarFile.toURI();
        for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
            File file = new File(baseUri.resolve(path));
            loadClassNames(file, newClassNameLocations);
        }
    }

    private static void loadClassNamesFromJarInputStream(JarInputStream jarIn, File jarFile,
            Multimap<String, File> newClassNameLocations) throws IOException {
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory()) {
                continue;
            }
            String name = jarEntry.getName();
            if (!name.endsWith(".class")) {
                continue;
            }
            String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
            newClassNameLocations.put(className, jarFile);
        }
    }

    private static File getFileFromJBossVfsURL(URL url, ClassLoader loader) throws Exception {
        Object virtualFile = url.openConnection().getContent();
        Class<?> virtualFileClass = loader.loadClass("org.jboss.vfs.VirtualFile");
        Method getPhysicalFileMethod = Reflections.getMethod(virtualFileClass, "getPhysicalFile");
        Method getNameMethod = Reflections.getMethod(virtualFileClass, "getName");
        File physicalFile = (File) Reflections.invoke(getPhysicalFileMethod, virtualFile);
        checkNotNull(physicalFile, "org.jboss.vfs.VirtualFile.getPhysicalFile() returned null");
        String name = (String) Reflections.invoke(getNameMethod, virtualFile);
        checkNotNull(name, "org.jboss.vfs.VirtualFile.getName() returned null");
        return new File(physicalFile.getParentFile(), name);
    }

    private static class PartialClassNameMatcher {

        private final String partialClassNameUpper;
        private final String prefixedPartialClassNameUpper1;
        private final String prefixedPartialClassNameUpper2;

        private PartialClassNameMatcher(String partialClassName) {
            partialClassNameUpper = partialClassName.toUpperCase(Locale.ENGLISH);
            prefixedPartialClassNameUpper1 = '.' + partialClassNameUpper;
            prefixedPartialClassNameUpper2 = '$' + partialClassNameUpper;
        }

        private boolean isPotentialFullMatch(String classNameUpper) {
            return classNameUpper.equals(partialClassNameUpper)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper1)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper2);
        }

        private boolean isPotentialMatch(String classNameUpper) {
            return classNameUpper.startsWith(partialClassNameUpper)
                    || classNameUpper.contains(prefixedPartialClassNameUpper1)
                    || classNameUpper.contains(prefixedPartialClassNameUpper2);
        }
    }

    private static class AnalyzingClassVisitor extends ClassVisitor {

        private final List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();

        private AnalyzingClassVisitor() {
            super(ASM5);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/[] exceptions) {
            if ((access & ACC_SYNTHETIC) != 0 || (access & ACC_NATIVE) != 0) {
                // don't add synthetic or native methods to the analyzed model
                return null;
            }
            if (name.equals("<init>")) {
                // don't add constructors to the analyzed model
                return null;
            }
            UiAnalyzedMethod.Builder builder = UiAnalyzedMethod.builder();
            builder.name(name);
            for (Type parameterType : Type.getArgumentTypes(desc)) {
                builder.addParameterTypes(parameterType.getClassName());
            }
            builder.returnType(Type.getReturnType(desc).getClassName());
            builder.modifiers(access);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    builder.addExceptions(ClassNames.fromInternalName(exception));
                }
            }
            analyzedMethods.add(builder.build());
            return null;
        }

        private List<UiAnalyzedMethod> getAnalyzedMethods() {
            return analyzedMethods;
        }
    }
}
