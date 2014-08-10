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
package org.glowroot.local.ui;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.GuardedBy;
import org.glowroot.markers.Singleton;
import org.glowroot.weaving.AnalyzedWorld;

import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final AnalyzedWorld analyzedWorld;

    // using sets of URIs because URLs have expensive equals and hashcode methods
    // see http://michaelscharf.blogspot.com/2006/11/javaneturlequals-and-hashcode-make.html
    private final Set<URI> classpathURIs =
            Sets.newSetFromMap(Maps.<URI, Boolean>newConcurrentMap());
    private final Map<String, Set<URI>> classNames = Maps.newConcurrentMap();

    @GuardedBy("classNameUppers")
    private final SortedMap<String, SortedSet<String>> classNameUppers = Maps.newTreeMap();

    ClasspathCache(AnalyzedWorld analyzedWorld) {
        this.analyzedWorld = analyzedWorld;
    }

    List<String> getMatchingClassNames(String partialClassName, int limit) {
        // update cache before proceeding
        updateCache();
        String partialClassNameUpper = partialClassName.toUpperCase(Locale.ENGLISH);
        Set<String> classNames = Sets.newTreeSet();
        synchronized (classNameUppers) {
            for (Entry<String, SortedSet<String>> entry : classNameUppers.entrySet()) {
                String classNameUpper = entry.getKey();
                if (classNameUpper.contains(partialClassNameUpper)) {
                    classNames.addAll(entry.getValue());
                    if (classNames.size() >= limit) {
                        return Lists.newArrayList(classNames).subList(0, limit);
                    }
                }
            }
        }
        return Lists.newArrayList(classNames);
    }

    ImmutableList<UiAnalyzedMethod> getAnalyzedMethods(String className) {
        // update cache before proceeding
        updateCache();
        List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();
        Set<URI> uris = classNames.get(className);
        if (uris == null) {
            return ImmutableList.of();
        }
        for (URI uri : uris) {
            try {
                analyzedMethods.addAll(getAnalyzedMethods(uri));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        return ImmutableList.copyOf(analyzedMethods);
    }

    void updateCache() {
        for (URLClassLoader loader : getKnownURLClassLoaders()) {
            updateCache(loader);
        }
        updateCacheWithBootstrapClasses();
    }

    private void updateCacheWithBootstrapClasses() {
        String bootClassPath = System.getProperty("sun.boot.class.path");
        if (bootClassPath == null) {
            return;
        }
        for (String path : Splitter.on(File.pathSeparatorChar).split(bootClassPath)) {
            URI uri = new File(path).toURI();
            synchronized (classpathURIs) {
                if (!classpathURIs.contains(uri)) {
                    loadClassNames(uri);
                    classpathURIs.add(uri);
                }
            }
        }
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(URI uri) throws IOException {
        AnalyzingClassVisitor cv = new AnalyzingClassVisitor();
        byte[] bytes = Resources.toByteArray(uri.toURL());
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.getAnalyzedMethods();
    }

    private void updateCache(URLClassLoader loader) {
        URL[] urls;
        try {
            urls = loader.getURLs();
        } catch (Exception e) {
            // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has been
            // called on the WebappClassLoader
            // (this happens, for example, after a webapp fails to load)
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return;
        }
        if (urls == null) {
            return;
        }
        List<URI> uris = Lists.newArrayList();
        for (URL url : urls) {
            try {
                uris.add(url.toURI());
            } catch (URISyntaxException e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        for (URI uri : uris) {
            synchronized (classpathURIs) {
                if (!classpathURIs.contains(uri)) {
                    loadClassNames(uri);
                    classpathURIs.add(uri);
                }
            }
        }
    }

    private Set<URLClassLoader> getKnownURLClassLoaders() {
        Set<URLClassLoader> loaders = Sets.newHashSet();
        for (ClassLoader loader : getKnownClassLoaders()) {
            while (loader != null) {
                if (loader instanceof URLClassLoader) {
                    loaders.add((URLClassLoader) loader);
                }
                loader = loader.getParent();
            }
        }
        return loaders;
    }

    private List<ClassLoader> getKnownClassLoaders() {
        List<ClassLoader> loaders = Lists.newArrayList(analyzedWorld.getClassLoaders());
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

    private void loadClassNames(URI uri) {
        try {
            if (uri.getScheme().equals("file")) {
                File file = new File(uri);
                if (file.isDirectory()) {
                    loadClassNamesFromDirectory(file, "");
                } else if (file.exists() && file.getName().endsWith(".jar")) {
                    loadClassNamesFromJarFile(uri);
                }
            } else if (uri.getPath().endsWith(".jar")) {
                // try to load jar from non-file uri
                loadClassNamesFromJarFile(uri);
            }
        } catch (IOException e) {
            logger.debug("error reading classes from uri: {}", uri, e);
        }
    }

    private void loadClassNamesFromDirectory(File dir, String prefix) throws MalformedURLException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class")) {
                URI fileUri = new File(dir, name).toURI();
                addClassName(prefix + name.substring(0, name.lastIndexOf('.')), fileUri);
            } else if (file.isDirectory()) {
                loadClassNamesFromDirectory(file, prefix + name + ".");
            }
        }
    }

    private void loadClassNamesFromJarFile(URI jarUri) throws IOException {
        Closer closer = Closer.create();
        JarInputStream jarIn = closer.register(new JarInputStream(jarUri.toURL().openStream()));
        try {
            Manifest manifest = jarIn.getManifest();
            if (manifest != null) {
                String classpath = manifest.getMainAttributes().getValue("Class-Path");
                if (classpath != null) {
                    for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
                        URI uri = jarUri.resolve(path);
                        loadClassNames(uri);
                    }
                }
            }
            JarEntry jarEntry;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                if (jarEntry.isDirectory()) {
                    continue;
                }
                String name = jarEntry.getName();
                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
                    // TODO test if this works with jar loaded over http protocol
                    try {
                        URI fileURI = new URI("jar", jarUri.getScheme() + ":" + jarUri.getPath()
                                + "!/" + name, "");
                        addClassName(className, fileURI);
                    } catch (URISyntaxException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private void addClassName(String className, URI uri) {
        Set<URI> uris = classNames.get(className);
        if (uris == null) {
            uris = Sets.newCopyOnWriteArraySet();
            classNames.put(className, uris);
        }
        uris.add(uri);
        addClassNameUpper(className);
    }

    private void addClassNameUpper(String className) {
        String classNameUpper = className.toUpperCase(Locale.ENGLISH);
        synchronized (classNameUppers) {
            SortedSet<String> classNames = classNameUppers.get(classNameUpper);
            if (classNames == null) {
                classNames = Sets.newTreeSet();
                classNameUppers.put(classNameUpper, classNames);
            }
            classNames.add(className);
        }
    }

    private static class AnalyzingClassVisitor extends ClassVisitor {

        private final List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();

        private AnalyzingClassVisitor() {
            super(ASM5);
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            if ((access & ACC_SYNTHETIC) == 0) {
                // don't add synthetic methods to the analyzed model
                List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(desc));
                Type returnType = Type.getReturnType(desc);
                List<String> exceptionList = exceptions == null ? ImmutableList.<String>of()
                        : Arrays.asList(exceptions);
                analyzedMethods.add(UiAnalyzedMethod.from(name, parameterTypes, returnType, access,
                        signature, exceptionList));
            }
            return null;
        }

        private List<UiAnalyzedMethod> getAnalyzedMethods() {
            return analyzedMethods;
        }
    }
}
