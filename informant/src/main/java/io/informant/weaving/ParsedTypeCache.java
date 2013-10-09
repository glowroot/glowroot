/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Singleton;

import static io.informant.common.Nullness.assertNonNull;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM4;

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
            findLoadedClassMethod = ClassLoader.class.getDeclaredMethod("findLoadedClass",
                    new Class[] {String.class});
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException("Unrecoverable error", e);
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException("Unrecoverable error", e);
        }
        findLoadedClassMethod.setAccessible(true);
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
    private final ConcurrentMap<String, ParsedType> bootLoaderParsedTypeCache =
            new ConcurrentHashMap<String, ParsedType>();

    @GuardedBy("typeNameUppers")
    private final SortedMap<String, SortedSet<String>> typeNameUppers = Maps.newTreeMap();

    public List<String> getMatchingTypeNames(String partialTypeName, int limit) {
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

    public List<Class<?>> getClassesWithAdhocPointcuts() {
        List<Class<?>> classes = Lists.newArrayList();
        for (Entry<ClassLoader, ConcurrentMap<String, ParsedType>> outerEntry : parsedTypeCache
                .asMap().entrySet()) {
            for (Entry<String, ParsedType> innerEntry : outerEntry.getValue().entrySet()) {
                if (innerEntry.getValue().hasAdhocPointcut()) {
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
        ParsedType parsedType = bootLoaderParsedTypeCache.get(typeName);
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

    public List<ClassLoader> getClassLoaders() {
        return ImmutableList.copyOf(parsedTypeCache.asMap().keySet());
    }

    void add(ParsedType parsedType, @Nullable ClassLoader loader) {
        ConcurrentMap<String, ParsedType> parsedTypes = getParsedTypes(loader);
        String typeName = parsedType.getName();
        parsedTypes.put(typeName, parsedType);
        addTypeNameUpper(typeName);
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    ImmutableList<ParsedType> getTypeHierarchy(@Nullable String typeName,
            @Nullable ClassLoader loader, ParseContext parseContext) {
        if (typeName == null || typeName.equals("java.lang.Object")) {
            return ImmutableList.of();
        }
        return ImmutableList.copyOf(getSuperTypes(typeName, loader, parseContext));
    }

    ParsedType getParsedType(String typeName, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        return getOrCreateParsedType(typeName, loader);
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    @ReadOnly
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
            logger.debug("type not found '{}' while parsing: {}", typeName, parseContext);
            return ImmutableList.of();
        }
        List<ParsedType> superTypes = Lists.newArrayList(parsedType);
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
            addTypeNameUpper(typeName);
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
            return bootLoaderParsedTypeCache.get(typeName);
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
            type = (Class<?>) findLoadedClassMethod.invoke(loader, typeName);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
        } catch (InvocationTargetException e) {
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
        ParsedTypeClassVisitor cv = new ParsedTypeClassVisitor();
        String path = TypeNames.toInternal(typeName) + ".class";
        URL url;
        if (loader == null) {
            // null loader means the bootstrap class loader
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return createParsedTypePlanB(typeName, loader);
        }
        byte[] bytes = Resources.toByteArray(url);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.build();
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
            logger.warn("could not find resource '{}.class' in class loader '{}', so the class"
                    + " was loaded during weaving of a subclass and was not woven itself",
                    TypeNames.toInternal(type.getName()), loader);
            return createParsedTypePlanC(typeName, type);
        } else {
            // the type was previously loaded so weaving was not bypassed, yay!
            return parsedType;
        }
    }

    // now that the type has been loaded anyways, build the parsed type via reflection
    private ParsedType createParsedTypePlanC(String typeName, Class<?> type) {
        ImmutableList.Builder<ParsedMethod> parsedMethods = ImmutableList.builder();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers()) || method.isSynthetic()) {
                // don't add native or synthetic methods to the parsed type model
                continue;
            }
            ImmutableList.Builder<Type> argTypes = ImmutableList.builder();
            for (Class<?> parameterType : method.getParameterTypes()) {
                argTypes.add(Type.getType(parameterType));
            }
            Type returnType = Type.getType(method.getReturnType());
            parsedMethods.add(ParsedMethod.from(method.getName(), argTypes.build(), returnType,
                    method.getModifiers()));
        }
        ImmutableList.Builder<String> interfaceNames = ImmutableList.builder();
        for (Class<?> interfaceClass : type.getInterfaces()) {
            interfaceNames.add(interfaceClass.getName());
        }
        Class<?> superclass = type.getSuperclass();
        String superName = superclass == null ? null : superclass.getName();
        return ParsedType.from(type.isInterface(), typeName, superName, interfaceNames.build(),
                parsedMethods.build());
    }

    private ConcurrentMap<String, ParsedType> getParsedTypes(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootLoaderParsedTypeCache;
        } else {
            return parsedTypeCache.getUnchecked(loader);
        }
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

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("parsedTypes", parsedTypeCache)
                .add("bootLoaderParsedTypes", bootLoaderParsedTypeCache)
                .toString();
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
        public String toString() {
            if (codeSource == null) {
                return className;
            } else {
                return className + " (" + codeSource.getLocation() + ")";
            }
        }
    }

    @Immutable
    public static class ParsedMethodOrdering extends Ordering<ParsedMethod> {

        public static final ParsedMethodOrdering INSTANCE = new ParsedMethodOrdering();

        @Override
        public int compare(@Nullable ParsedMethod left, @Nullable ParsedMethod right) {
            assertNonNull(left, "Comparing of non-null elements only");
            assertNonNull(right, "Comparing of non-null elements only");
            return ComparisonChain.start()
                    .compare(getAccessibility(left), getAccessibility(right))
                    .compare(left.getName(), right.getName())
                    .compare(left.getArgTypeNames().size(), right.getArgTypeNames().size())
                    .compare(left.getArgTypeNames().size(), right.getArgTypeNames().size())
                    .result();
        }

        private static int getAccessibility(ParsedMethod parsedMethod) {
            int modifiers = parsedMethod.getModifiers();
            if (Modifier.isPublic(modifiers)) {
                return 1;
            } else if (Modifier.isProtected(modifiers)) {
                return 2;
            } else if (Modifier.isPrivate(modifiers)) {
                return 4;
            } else {
                // package-private
                return 3;
            }
        }
    }

    public static class ParsedTypeClassVisitor extends ClassVisitor {

        private boolean iface;
        @Nullable
        private String name;
        @Nullable
        private String superName;
        private String/*@Nullable*/[] interfaceNames;
        private final ImmutableList.Builder<ParsedMethod> methods = ImmutableList.builder();

        public ParsedTypeClassVisitor() {
            super(ASM4);
        }

        @Override
        public void visit(int version, int access, String name, @Nullable String signature,
                @Nullable String superName, String/*@Nullable*/[] interfaceNames) {
            this.iface = Modifier.isInterface(access);
            this.name = name;
            if (superName == null || superName.equals("java/lang/Object")) {
                this.superName = null;
            } else {
                this.superName = superName;
            }
            this.interfaceNames = interfaceNames;
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            if ((access & (ACC_NATIVE | ACC_SYNTHETIC)) == 0) {
                // don't add native or synthetic methods to the parsed type model
                methods.add(ParsedMethod.from(name,
                        ImmutableList.copyOf(Type.getArgumentTypes(desc)),
                        Type.getReturnType(desc), access));
            }
            return null;
        }

        public ParsedType build() {
            assertNonNull(name, "Call to visit() is required");
            return ParsedType.from(iface, TypeNames.fromInternal(name),
                    TypeNames.fromInternal(superName), TypeNames.fromInternal(interfaceNames),
                    methods.build());
        }
    }
}
