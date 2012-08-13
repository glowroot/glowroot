/**
 * Copyright 2012 the original author or authors.
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ParsedTypeCache {

    private static final Logger logger = LoggerFactory.getLogger(ParsedTypeCache.class);

    private static final Method findLoadedClassMethod;

    static {
        try {
            findLoadedClassMethod = ClassLoader.class.getDeclaredMethod("findLoadedClass",
                    new Class[] { String.class });
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException("Unrecoverable error", e);
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException("Unrecoverable error", e);
        }
        findLoadedClassMethod.setAccessible(true);
    }

    // TODO make me readable
    private final LoadingCache<Optional<ClassLoader>, LoadingCache<String, ParsedType>> parsedTypes =
            CacheBuilder.newBuilder().build(
                    new CacheLoader<Optional<ClassLoader>, LoadingCache<String, ParsedType>>() {
                        @Override
                        public LoadingCache<String, ParsedType> load(
                                final Optional<ClassLoader> loader) {
                            return CacheBuilder.newBuilder().build(
                                    new CacheLoader<String, ParsedType>() {
                                        @Override
                                        public ParsedType load(String typeName) {
                                            return createParsedType(typeName, loader.orNull());
                                        }
                                    });
                        }
                    });

    void add(ParsedType parsedType, @Nullable ClassLoader loader) {
        parsedTypes.getUnchecked(Optional.fromNullable(loader)).put(parsedType.getName(),
                parsedType);
    }

    // TODO is it worth removing duplicates from resulting type hierarchy list?
    ImmutableList<ParsedType> getTypeHierarchy(@Nullable String typeName,
            @Nullable ClassLoader loader) {

        ImmutableList.Builder<ParsedType> superTypes = ImmutableList.builder();
        addSuperTypes(typeName, superTypes, loader);
        return superTypes.build();
    }

    private void addSuperTypes(@Nullable String typeName,
            ImmutableList.Builder<ParsedType> superTypes, @Nullable ClassLoader loader) {

        if (typeName == null || typeName.equals("java/lang/Object")) {
            return;
        }
        // can't call Class.forName() since that bypasses ClassFileTransformer.transform() if the
        // class hasn't already been loaded, so instead, call the package protected
        // ClassLoader.findLoadClass()
        Class<?> type;
        try {
            type = (Class<?>) findLoadedClassMethod.invoke(loader, typeName.replace('/', '.'));
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            superTypes.add(ParsedType.fromMissing(typeName));
            return;
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            superTypes.add(ParsedType.fromMissing(typeName));
            return;
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            superTypes.add(ParsedType.fromMissing(typeName));
            return;
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            superTypes.add(ParsedType.fromMissing(typeName));
            return;
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
        ParsedType parsedType = parsedTypes.getUnchecked(Optional.fromNullable(parsedTypeLoader))
                .getUnchecked(typeName);
        superTypes.add(parsedType);
        addSuperTypes(parsedType.getSuperName(), superTypes, loader);
        for (String interfaceName : parsedType.getInterfaceNames()) {
            addSuperTypes(interfaceName, superTypes, loader);
        }
    }

    private ParsedType createParsedType(String typeName, @Nullable ClassLoader loader) {
        ParsedTypeClassVisitor cv = new ParsedTypeClassVisitor();
        String path = typeName + ".class";
        URL url;
        if (loader == null) {
            url = ClassLoader.getSystemClassLoader().getResource(path);
        } else {
            url = loader.getResource(path);
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return createParsedTypePlanB(typeName, loader);
        }
        try {
            byte[] bytes = ByteStreams.toByteArray(Resources.newInputStreamSupplier(url));
            ClassReader cr = new ClassReader(bytes);
            cr.accept(cv, 0);
            return cv.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ParsedType.fromMissing(typeName);
        }
    }

    private ParsedType createParsedTypePlanB(String typeName,
            @Nullable ClassLoader loader) {

        Class<?> type;
        try {
            type = Class.forName(typeName.replace('/', '.'), false, loader);
        } catch (ClassNotFoundException e) {
            logger.warn("could not find type '{}' in class loader '{}'",
                    typeName.replace('/', '.'), loader);
            return ParsedType.fromMissing(typeName);
        }
        ParsedType parsedType = parsedTypes.getUnchecked(
                Optional.fromNullable(type.getClassLoader())).getIfPresent(typeName);
        if (parsedType == null) {
            // a class was loaded that was not previously loaded which means weaving was bypassed
            // since ClassFileTransformer.transform() is not re-entrant
            logger.warn("could not find resource '{}.class' in class loader '{}', so the class"
                    + " was loaded during weaving of a subclass and was not woven itself", type,
                    loader);
            return createParsedTypePlanC(typeName, type);
        } else {
            return parsedType;
        }
    }

    // now that the type has been loaded anyways, build the parsed type via reflection
    private ParsedType createParsedTypePlanC(String typeName, Class<?> type) {
        ImmutableList.Builder<ParsedMethod> parsedMethods = ImmutableList.builder();
        for (Method method : type.getDeclaredMethods()) {
            Type[] args = new Type[method.getParameterTypes().length];
            for (int j = 0; j < args.length; j++) {
                args[j] = Type.getType(method.getParameterTypes()[j]);
            }
            parsedMethods.add(ParsedMethod.from(method.getName(), args));
        }
        ImmutableList.Builder<String> interfaceNames = ImmutableList.builder();
        for (Class<?> iface : type.getInterfaces()) {
            interfaceNames.add(internalName(iface.getName()));
        }
        return ParsedType.from(typeName, internalName(type.getSuperclass().getName()),
                interfaceNames.build(), parsedMethods.build());
    }

    private static String internalName(String typeName) {
        return typeName.replace('.', '/');
    }

    private static class ParsedTypeClassVisitor extends ClassVisitor {

        @Nullable
        private String name;
        @Nullable
        private String superName;
        @Nullable
        private ImmutableList<String> interfaceNames;
        private final ImmutableList.Builder<ParsedMethod> methods = ImmutableList.builder();

        private ParsedTypeClassVisitor() {
            super(Opcodes.ASM4);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                @Nullable String superName, String[] interfaceNames) {

            this.name = name;
            if (superName == null || superName.equals("java/lang/Object")) {
                this.superName = null;
            } else {
                this.superName = superName;
            }
            this.interfaceNames = ImmutableList.copyOf(interfaceNames);
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, @Nullable String[] exceptions) {

            methods.add(ParsedMethod.from(name, Type.getArgumentTypes(desc)));
            return null;
        }

        private ParsedType build() {
            return ParsedType.from(name, superName, interfaceNames, methods.build());
        }
    }
}
