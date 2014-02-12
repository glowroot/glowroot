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

import checkers.lock.quals.GuardedBy;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Singleton;
import org.glowroot.weaving.ParsedType;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.ParsedTypeCache.ParsedTypeClassVisitor;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final ParsedTypeCache parsedTypeCache;

    // using sets of URIs because URLs have expensive equals and hashcode methods
    // see http://michaelscharf.blogspot.com/2006/11/javaneturlequals-and-hashcode-make.html
    private final Set<URI> classpathURIs =
            Sets.newSetFromMap(Maps.<URI, Boolean>newConcurrentMap());
    private final Map<String, Set<URI>> typeNames = Maps.newConcurrentMap();

    @GuardedBy("typeNameUppers")
    private final SortedMap<String, SortedSet<String>> typeNameUppers = Maps.newTreeMap();

    ClasspathCache(ParsedTypeCache parsedTypeCache) {
        this.parsedTypeCache = parsedTypeCache;
    }

    List<String> getMatchingTypeNames(String partialTypeName, int limit) {
        // update cache before proceeding
        updateCache();
        String partialTypeNameUpper = partialTypeName.toUpperCase(Locale.ENGLISH);
        Set<String> typeNames = Sets.newTreeSet();
        synchronized (typeNameUppers) {
            for (Entry<String, SortedSet<String>> entry : typeNameUppers.entrySet()) {
                String typeNameUpper = entry.getKey();
                if (typeNameUpper.contains(partialTypeNameUpper)) {
                    typeNames.addAll(entry.getValue());
                    if (typeNames.size() >= limit) {
                        return Lists.newArrayList(typeNames).subList(0, limit);
                    }
                }
            }
        }
        return Lists.newArrayList(typeNames);
    }

    ImmutableList<ParsedType> getParsedTypes(String typeName) {
        // update cache before proceeding
        updateCache();
        ImmutableList.Builder<ParsedType> parsedTypes = ImmutableList.builder();
        Set<URI> uris = typeNames.get(typeName);
        if (uris == null) {
            return ImmutableList.of();
        }
        for (URI uri : uris) {
            try {
                parsedTypes.add(createParsedType(uri));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        return parsedTypes.build();
    }

    void updateCache() {
        for (URLClassLoader loader : getKnownURLClassLoaders()) {
            updateCache(loader);
        }
    }

    private ParsedType createParsedType(URI uri) throws IOException {
        ParsedTypeClassVisitor cv = new ParsedTypeClassVisitor();
        byte[] bytes = Resources.toByteArray(uri.toURL());
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.build();
    }

    private void updateCache(URLClassLoader loader) {
        URL[] urls;
        try {
            urls = loader.getURLs();
        } catch (Exception e) {
            // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has been
            // called on the WebappClassLoader
            // (this happens, for example, after a webapp fails to load)
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
            }
        }
        for (URI uri : uris) {
            synchronized (classpathURIs) {
                if (!classpathURIs.contains(uri)) {
                    loadTypeNames(uri);
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
        List<ClassLoader> loaders = Lists.newArrayList(parsedTypeCache.getClassLoaders());
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

    private void loadTypeNames(URI uri) {
        try {
            if (uri.getScheme().equals("file")) {
                File file = new File(uri);
                if (file.isDirectory()) {
                    loadTypeNamesFromDirectory(file, "");
                } else if (file.exists() && file.getName().endsWith(".jar")) {
                    loadTypeNamesFromJarFile(uri);
                }
            } else if (uri.getPath().endsWith(".jar")) {
                // try to load jar from non-file uri
                loadTypeNamesFromJarFile(uri);
            }
        } catch (IOException e) {
            logger.debug("error reading classes from uri: {}", uri, e);
        }
    }

    private void loadTypeNamesFromDirectory(File dir, String prefix) throws MalformedURLException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class")) {
                URI fileUri = new File(dir, name).toURI();
                addTypeName(prefix + name.substring(0, name.lastIndexOf('.')), fileUri);
            } else if (file.isDirectory()) {
                loadTypeNamesFromDirectory(file, prefix + name + ".");
            }
        }
    }

    private void loadTypeNamesFromJarFile(URI jarUri) throws IOException {
        JarInputStream jarIn = new JarInputStream(jarUri.toURL().openStream());
        Manifest manifest = jarIn.getManifest();
        if (manifest != null) {
            String classpath = manifest.getMainAttributes().getValue("Class-Path");
            if (classpath != null) {
                for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
                    URI uri = jarUri.resolve(path);
                    loadTypeNames(uri);
                }
            }
        }
        try {
            JarEntry jarEntry;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                if (jarEntry.isDirectory()) {
                    continue;
                }
                String name = jarEntry.getName();
                if (name.endsWith(".class")) {
                    String typeName = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
                    // TODO test if this works with jar loaded over http protocol
                    URI fileURI = new URI("jar", jarUri.getScheme() + ":" + jarUri.getPath()
                            + "!/" + name, "");
                    addTypeName(typeName, fileURI);
                }
            }
        } catch (URISyntaxException e) {
            logger.error(e.getMessage(), e);
        } finally {
            jarIn.close();
        }
    }

    private void addTypeName(String typeName, URI uri) {
        Set<URI> uris = typeNames.get(typeName);
        if (uris == null) {
            uris = Sets.newCopyOnWriteArraySet();
            typeNames.put(typeName, uris);
        }
        uris.add(uri);
        addTypeNameUpper(typeName);
    }

    private void addTypeNameUpper(String typeName) {
        String typeNameUpper = typeName.toUpperCase(Locale.ENGLISH);
        synchronized (typeNameUppers) {
            SortedSet<String> typeNames = typeNameUppers.get(typeNameUpper);
            if (typeNames == null) {
                typeNames = Sets.newTreeSet();
                typeNameUppers.put(typeNameUpper, typeNames);
            }
            typeNames.add(typeName);
        }
    }
}
