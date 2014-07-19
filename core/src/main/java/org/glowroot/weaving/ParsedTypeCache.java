/*
 * Copyright 2012-2014 the original author or authors.
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

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Objects;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ParsedTypeCache {

    private static final Logger logger = LoggerFactory.getLogger(ParsedTypeCache.class);

    private static final Method findLoadedClassMethod;

    static {
        try {
            findLoadedClassMethod = Reflections.getDeclaredMethod(ClassLoader.class,
                    "findLoadedClass", new Class[] {String.class});
        } catch (ReflectiveException e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // weak keys to prevent retention of class loaders
    // it's important that the weak keys point directly to the class loaders themselves (as opposed
    // to through another instance, e.g. Optional<ClassLoader>) so that the keys won't be cleared
    // while their associated class loaders are still being used
    //
    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    private final LoadingCache<ClassLoader, ConcurrentMap<String, ParsedType>> parsedTypeCache =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<ClassLoader, ConcurrentMap<String, ParsedType>>() {
                        @Override
                        public ConcurrentMap<String, ParsedType> load(ClassLoader loader) {
                            // intentionally avoiding Maps.newConcurrentMap() since it uses many
                            // additional classes that must then be pre-initialized since this
                            // is called from inside ClassFileTransformer.transform()
                            // (see PreInitializeClasses)
                            return new ConcurrentHashMap<String, ParsedType>();
                        }
                    });

    // the parsed types for the bootstrap class loader (null) have to be stored separately since
    // LoadingCache doesn't accept null keys, and using an Optional<ClassLoader> for the key makes
    // the weakness on the Optional instance which is not strongly referenced from anywhere and
    // therefore the keys will most likely be cleared while their class loaders are still being used
    //
    // intentionally avoiding Maps.newConcurrentMap() for the same reason as above
    private final ConcurrentMap<String, ParsedType> bootstrapLoaderParsedTypeCache =
            new ConcurrentHashMap<String, ParsedType>();

    private final Supplier<ImmutableList<Advice>> advisors;
    private final ImmutableList<MixinType> mixinTypes;

    private final ParsedType javaLangObjectParsedType;

    public ParsedTypeCache(Supplier<ImmutableList<Advice>> advisors, List<MixinType> mixinTypes) {
        this.advisors = advisors;
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        javaLangObjectParsedType = createParsedTypePlanC(Object.class, advisors.get());
    }

    public List<Class<?>> getClassesWithReweavableAdvice() {
        List<Class<?>> classes = Lists.newArrayList();
        for (Entry<ClassLoader, ConcurrentMap<String, ParsedType>> outerEntry : parsedTypeCache
                .asMap().entrySet()) {
            for (Entry<String, ParsedType> innerEntry : outerEntry.getValue().entrySet()) {
                if (innerEntry.getValue().hasReweavableAdvice()) {
                    try {
                        classes.add(outerEntry.getKey().loadClass(innerEntry.getKey()));
                    } catch (ClassNotFoundException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
        return classes;
    }

    public void clearClassesBeforeReweaving(Iterable<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            ClassLoader loader = clazz.getClassLoader();
            if (loader == null) {
                bootstrapLoaderParsedTypeCache.remove(clazz.getName());
            } else {
                parsedTypeCache.getUnchecked(loader).remove(clazz.getName());
            }
        }
    }

    public List<Class<?>> getExistingSubClasses(Set<String> rootTypeNames) {
        List<Class<?>> classes = Lists.newArrayList();
        for (ClassLoader loader : parsedTypeCache.asMap().keySet()) {
            classes.addAll(getExistingSubClasses(rootTypeNames, loader));
        }
        classes.addAll(getExistingSubClasses(rootTypeNames, null));
        return classes;
    }

    public List<ParsedType> getParsedTypes(String typeName) {
        List<ParsedType> parsedTypes = Lists.newArrayList();
        ParsedType parsedType = bootstrapLoaderParsedTypeCache.get(typeName);
        if (parsedType != null) {
            parsedTypes.add(parsedType);
        }
        for (Map<String, ParsedType> loaderParsedTypes : this.parsedTypeCache.asMap().values()) {
            parsedType = loaderParsedTypes.get(typeName);
            if (parsedType != null) {
                parsedTypes.add(parsedType);
            }
        }
        return parsedTypes;
    }

    public ImmutableList<ClassLoader> getClassLoaders() {
        return ImmutableList.copyOf(parsedTypeCache.asMap().keySet());
    }

    void add(ParsedType parsedType, @Nullable ClassLoader loader) {
        ConcurrentMap<String, ParsedType> loaderParsedTypes = getParsedTypes(loader);
        String typeName = parsedType.getName();
        loaderParsedTypes.put(typeName, parsedType);
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    List<ParsedType> getTypeHierarchy(@Nullable String typeName, @Nullable ClassLoader loader,
            ParseContext parseContext) {
        if (typeName == null || typeName.equals("java.lang.Object")) {
            return ImmutableList.of();
        }
        return getSuperTypes(typeName, loader, parseContext);
    }

    ParsedType getParsedType(String typeName, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        return getOrCreateParsedType(typeName, loader);
    }

    ParsedType getJavaLangObjectParsedType() {
        return javaLangObjectParsedType;
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    private List<ParsedType> getSuperTypes(String typeName, @Nullable ClassLoader loader,
            ParseContext parseContext) {
        ParsedType parsedType;
        try {
            parsedType = getOrCreateParsedType(typeName, loader);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (ClassNotFoundException e) {
            // log at debug level only since the code referencing the class must not be getting used
            // anyways, as it would fail on execution since the type doesn't exist
            logger.debug("type {} not found while parsing type {}", typeName, parseContext, e);
            return ImmutableList.of();
        }
        List<ParsedType> superTypes = Lists.newArrayList();
        superTypes.add(parsedType);
        String superName = parsedType.getSuperName();
        if (superName != null && !superName.equals("java.lang.Object")) {
            superTypes.addAll(getSuperTypes(superName, loader, parseContext));
        }
        for (String interfaceName : parsedType.getInterfaceNames()) {
            superTypes.addAll(getSuperTypes(interfaceName, loader, parseContext));
        }
        return superTypes;
    }

    private ParsedType getOrCreateParsedType(String typeName, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        ClassLoader parsedTypeLoader = getParsedTypeLoader(typeName, loader);
        ConcurrentMap<String, ParsedType> loaderParsedTypes = getParsedTypes(parsedTypeLoader);
        ParsedType parsedType = loaderParsedTypes.get(typeName);
        if (parsedType == null) {
            parsedType = createParsedType(typeName, parsedTypeLoader);
            ParsedType storedParsedType = loaderParsedTypes.putIfAbsent(typeName, parsedType);
            if (storedParsedType != null) {
                // (rare) concurrent ParsedType creation, use the one that made it into the map
                parsedType = storedParsedType;
            }
        }
        return parsedType;
    }

    private List<Class<?>> getExistingSubClasses(Set<String> rootTypeNames,
            @Nullable ClassLoader loader) {
        List<Class<?>> classes = Lists.newArrayList();
        for (ParsedType parsedType : getParsedTypes(loader).values()) {
            if (isSubClass(parsedType, rootTypeNames, loader)) {
                try {
                    classes.add(Class.forName(parsedType.getName(), false, loader));
                } catch (ClassNotFoundException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return classes;
    }

    private boolean isSubClass(ParsedType parsedType, Set<String> rootTypeNames,
            @Nullable ClassLoader loader) {
        List<String> superTypeNames = getExistingTypeHierarchy(parsedType, loader);
        for (String superTypeName : superTypeNames) {
            if (rootTypeNames.contains(superTypeName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getExistingTypeHierarchy(ParsedType parsedType,
            @Nullable ClassLoader loader) {
        List<String> superTypes = Lists.newArrayList(parsedType.getName());
        String superName = parsedType.getSuperName();
        if (superName != null && !superName.equals("java.lang.Object")) {
            ParsedType superParsedType = getExistingParsedType(superName, loader);
            if (superParsedType != null) {
                superTypes.addAll(getExistingTypeHierarchy(superParsedType, loader));
            }
        }
        for (String interfaceName : parsedType.getInterfaceNames()) {
            ParsedType interfaceParsedType = getExistingParsedType(interfaceName, loader);
            if (interfaceParsedType != null) {
                superTypes.addAll(getExistingTypeHierarchy(interfaceParsedType, loader));
            }
        }
        return superTypes;
    }

    @Nullable
    private ParsedType getExistingParsedType(String typeName, @Nullable ClassLoader loader) {
        ClassLoader parsedTypeLoader = getParsedTypeLoader(typeName, loader);
        if (parsedTypeLoader == null) {
            return bootstrapLoaderParsedTypeCache.get(typeName);
        }
        return parsedTypeCache.getUnchecked(parsedTypeLoader).get(typeName);
    }

    @Nullable
    private ClassLoader getParsedTypeLoader(String typeName, @Nullable ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        // can't call Class.forName() since that bypasses ClassFileTransformer.transform() if the
        // class hasn't already been loaded, so instead, call the package protected
        // ClassLoader.findLoadClass()
        Class<?> type = null;
        try {
            type = (Class<?>) Reflections.invoke(findLoadedClassMethod, loader, typeName);
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
        }
        ClassLoader parsedTypeLoader = loader;
        if (type != null) {
            // this type has already been loaded, so the corresponding parsedType should already be
            // in the cache under its class loader
            //
            // this helps in cases where the .class files are not available via
            // ClassLoader.getResource(), as well as being a good optimization in other cases
            parsedTypeLoader = type.getClassLoader();
        }
        return parsedTypeLoader;
    }

    private ParsedType createParsedType(String typeName, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        String path = TypeNames.toInternal(typeName) + ".class";
        URL url;
        if (loader == null) {
            // null loader means the bootstrap class loader
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
            if (url != null) {
                ClassLoader tempLoader = loader;
                while (tempLoader != null) {
                    ClassLoader parentLoader = tempLoader.getParent();
                    URL parentLoaderUrl;
                    if (parentLoader == null) {
                        parentLoaderUrl = ClassLoader.getSystemResource(path);
                    } else {
                        parentLoaderUrl = parentLoader.getResource(path);
                    }
                    if (url.equals(parentLoaderUrl)) {
                        // reuse parent loader's ParsedType if available
                        // this saves time here, and reduces memory footprint of ParsedTypeCache
                        // which can be very noticeable when lots of ClassLoaders, e.g. groovy
                        ParsedType parentLoaderParsedType =
                                getParsedTypes(parentLoader).get(typeName);
                        if (parentLoaderParsedType != null) {
                            return parentLoaderParsedType;
                        }
                    }
                    tempLoader = parentLoader;
                }
            }
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return createParsedTypePlanB(typeName, loader);
        }
        ParsedTypeClassVisitor cv =
                new ParsedTypeClassVisitor(advisors.get(), mixinTypes, loader, this, null);
        byte[] bytes = Resources.toByteArray(url);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        ParsedType parsedType = cv.getParsedType();
        checkNotNull(parsedType); // parsedType is non-null after visiting the class
        return parsedType;
    }

    // plan B covers some class loaders like
    // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader that delegate loadClass() to some
    // other loader where the type may have already been loaded
    private ParsedType createParsedTypePlanB(String typeName, @Nullable ClassLoader loader)
            throws ClassNotFoundException {
        Class<?> type = Class.forName(typeName, false, loader);
        ParsedType parsedType = getParsedTypes(type.getClassLoader()).get(typeName);
        if (parsedType == null) {
            // a class was loaded by Class.forName() above that was not previously loaded which
            // means weaving was bypassed since ClassFileTransformer.transform() is not re-entrant

            // TODO inspect the class after loading to see if any advice even applies to it, if not
            // then no need to log warning
            logger.warn("could not find resource {}.class in class loader {}, so the class"
                    + " had to be loaded using Class.forName() during weaving of one of its"
                    + " subclasses, which means it was not woven itself since weaving is not"
                    + " re-entrant", TypeNames.toInternal(type.getName()), loader);
            return createParsedTypePlanC(type, advisors.get());
        } else {
            // the type was previously loaded so weaving was not bypassed, yay!
            return parsedType;
        }
    }

    private ConcurrentMap<String, ParsedType> getParsedTypes(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootstrapLoaderParsedTypeCache;
        } else {
            return parsedTypeCache.getUnchecked(loader);
        }
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("parsedTypes", parsedTypeCache)
                .add("bootstrapLoaderParsedTypes", bootstrapLoaderParsedTypeCache)
                .toString();
    }

    // now that the type has been loaded anyways, build the parsed type via reflection
    private static ParsedType createParsedTypePlanC(Class<?> type, ImmutableList<Advice> advisors) {
        List<AdviceMatcher> adviceMatchers =
                AdviceMatcher.getAdviceMatchers(type.getName(), advisors);
        List<ParsedMethod> parsedMethods = Lists.newArrayList();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                // don't add synthetic methods to the parsed type model
                continue;
            }
            List<Type> parameterTypes = Lists.newArrayList();
            for (Class<?> parameterType : method.getParameterTypes()) {
                parameterTypes.add(Type.getType(parameterType));
            }
            Type returnType = Type.getType(method.getReturnType());
            List<Advice> matchingAdvisors = getMatchingAdvisors(method.getModifiers(),
                    method.getName(), parameterTypes, returnType, adviceMatchers);
            if (!matchingAdvisors.isEmpty() && (method.getModifiers() & ACC_SYNTHETIC) == 0) {
                // don't add synthetic methods to the parsed type model
                List<String> exceptions = Lists.newArrayList();
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    exceptions.add(Type.getInternalName(exceptionType));
                }
                ParsedMethod parsedMethod = ParsedMethod.from(method.getName(), parameterTypes,
                        returnType, method.getModifiers(), null, exceptions, matchingAdvisors);
                parsedMethods.add(parsedMethod);
            }
        }
        List<String> interfaceNames = Lists.newArrayList();
        for (Class<?> interfaceClass : type.getInterfaces()) {
            interfaceNames.add(interfaceClass.getName());
        }
        Class<?> superclass = type.getSuperclass();
        String superName = superclass == null ? null : superclass.getName();
        return ParsedType.from(type.getModifiers(), type.getName(), superName, interfaceNames,
                parsedMethods);
    }

    private static List<Advice> getMatchingAdvisors(int access, String name,
            List<Type> parameterTypes, Type returnType, List<AdviceMatcher> adviceMatchers) {
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(name, parameterTypes, returnType, access)) {
                matchingAdvisors.add(adviceMatcher.getAdvice());
            }
        }
        return matchingAdvisors;
    }

    static class ParseContext {
        private final String className;
        @Nullable
        private final CodeSource codeSource;
        ParseContext(String className, @Nullable CodeSource codeSource) {
            this.codeSource = codeSource;
            this.className = className;
        }
        // toString() is used in logger warning construction
        @Override
        @Pure
        public String toString() {
            if (codeSource == null) {
                return className;
            } else {
                return className + " (" + codeSource.getLocation() + ")";
            }
        }
    }
}
