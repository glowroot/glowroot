/*
 * Copyright 2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
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

import io.informant.markers.Singleton;
import io.informant.weaving.ParsedType;
import io.informant.weaving.ParsedTypeCache;
import io.informant.weaving.ParsedTypeCache.ParsedTypeClassVisitor;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final ParsedTypeCache parsedTypeCache;

    private final Set<URL> classpath = Sets.newSetFromMap(Maps.<URL, Boolean>newConcurrentMap());
    private final Map<String, Set<URL>> typeNames = Maps.newConcurrentMap();

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

    List<ParsedType> getParsedTypes(String typeName) {
        // update cache before proceeding
        updateCache();
        List<ParsedType> parsedTypes = Lists.newArrayList();
        Set<URL> urls = typeNames.get(typeName);
        if (urls == null) {
            return ImmutableList.of();
        }
        for (URL url : urls) {
            try {
                parsedTypes.add(createParsedType(url));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        return parsedTypes;
    }

    private ParsedType createParsedType(URL url) throws IOException {
        ParsedTypeClassVisitor cv = new ParsedTypeClassVisitor();
        byte[] bytes = Resources.toByteArray(url);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.build();
    }

    private void updateCache() {
        for (ClassLoader loader : getKnownClassLoaders()) {
            if (!(loader instanceof URLClassLoader)) {
                continue;
            }
            updateCache(loader);
        }
    }

    private void updateCache(ClassLoader loader) {
        URL[] urls;
        try {
            urls = ((URLClassLoader) loader).getURLs();
        } catch (Exception e) {
            // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has been
            // called on the WebappClassLoader
            // (this happens, for example, after a webapp fails to load)
            return;
        }
        if (urls == null) {
            return;
        }
        for (URL url : urls) {
            if (classpath.contains(url)) {
                continue;
            }
            classpath.add(url);
            try {
                loadTypeNames(url);
            } catch (IOException e) {
                logger.debug("unable to read classes from url: " + url, e);
            } catch (URISyntaxException e) {
                logger.debug("unable to read classes from url: " + url, e);
            }
        }
        ClassLoader parent = loader.getParent();
        if (parent != null) {
            updateCache(parent);
        }
    }

    private List<ClassLoader> getKnownClassLoaders() {
        List<ClassLoader> loaders = Lists.newArrayList(parsedTypeCache.getClassLoaders());
        if (loaders.isEmpty()) {
            // this is needed for testing the UI outside of javaagent
            return ImmutableList.of(ClassLoader.getSystemClassLoader());
        }
        return loaders;
    }

    private void loadTypeNames(URL url) throws IOException, URISyntaxException {
        if (url.getProtocol().equals("file")) {
            File file = new File(url.toURI());
            if (file.isDirectory()) {
                loadTypeNamesFromDirectory(file, "");
            } else {
                loadTypeNamesFromJarFile(url);
            }
        } else if (url.getPath().endsWith(".jar")) {
            // try to load jar from non-file url
            loadTypeNamesFromJarFile(url);
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
                URL fileUrl = new File(dir, name).toURL();
                addTypeName(prefix + name.substring(0, name.lastIndexOf('.')), fileUrl);
            } else if (file.isDirectory()) {
                loadTypeNamesFromDirectory(file, prefix + name + ".");
            }
        }
    }

    private void loadTypeNamesFromJarFile(URL jarUrl) throws IOException {
        JarInputStream jarIn = new JarInputStream(jarUrl.openStream());
        Manifest manifest = jarIn.getManifest();
        if (manifest != null) {
            String classpath = manifest.getMainAttributes().getValue("Class-Path");
            if (classpath != null) {
                for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
                    loadTypeNamesFromJarFile(new URL(path));
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
                    URL fileURL = new URL("jar", "", jarUrl.getProtocol() + ":" + jarUrl.getFile()
                            + "!/" + name);
                    addTypeName(typeName, fileURL);
                }
            }
        } finally {
            jarIn.close();
        }
    }

    private void addTypeName(String typeName, URL url) {
        Set<URL> urls = typeNames.get(typeName);
        if (urls == null) {
            urls = Sets.newCopyOnWriteArraySet();
            typeNames.put(typeName, urls);
        }
        urls.add(url);
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
