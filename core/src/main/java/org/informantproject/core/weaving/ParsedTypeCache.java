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
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.informantproject.core.weaving.ParsedType.ParsedMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ParsedTypeCache {

    private static final Logger logger = LoggerFactory.getLogger(ParsedTypeCache.class);

    private final ClassLoader loader;

    // for performance sensitive areas do not use guava's LoadingCache due to volatile write (via
    // incrementing an AtomicInteger) at the end of get() in LocalCache$Segment.postReadCleanup()
    private final Map<String, List<ParsedType>> typeHierarchies = Maps.newConcurrentMap();

    public ParsedTypeCache(ClassLoader loader) {
        this.loader = loader;
    }

    public List<ParsedType> getTypeHierarchy(@Nullable String typeName) {
        if (typeName == null || typeName.equals("java/lang/Object")) {
            return ImmutableList.of();
        }
        List<ParsedType> typeHierarchy = typeHierarchies.get(typeName);
        if (typeHierarchy == null) {
            // just a cache, ok if two threads happen to instantiate and store in parallel
            typeHierarchy = loadTypeHierarchy(typeName);
            typeHierarchies.put(typeName, typeHierarchy);
        }
        return typeHierarchy;
    }

    // TODO is it worth removing duplicates from resulting type hierarchy list?
    private List<ParsedType> loadTypeHierarchy(String typeName) {
        ParsedType parsedType = loadParsedType(typeName);
        ImmutableList.Builder<ParsedType> builder = new Builder<ParsedType>();
        builder.add(parsedType);
        String superName = parsedType.getSuperName();
        if (superName != null) {
            builder.addAll(loadTypeHierarchy(superName));
        }
        for (String interfaceName : parsedType.getInterfaceNames()) {
            builder.addAll(loadTypeHierarchy(interfaceName));
        }
        return builder.build();
    }

    private ParsedType loadParsedType(String typeName) {
        ParsedTypeBuilder cv = new ParsedTypeBuilder();
        InputStream inputStream = loader.getResourceAsStream(typeName + ".class");
        if (inputStream == null) {
            return new ParsedType(typeName);
        }
        try {
            ClassReader cr = new ClassReader(inputStream);
            cr.accept(cv, 0);
            return cv.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return new ParsedType(typeName);
        }
    }

    private static class ParsedTypeBuilder extends ClassVisitor {

        private String name;
        private String superName;
        private String[] interfaceNames;
        private final List<ParsedMethod> methods = Lists.newArrayList();

        private ParsedTypeBuilder() {
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
            this.interfaceNames = interfaceNames;
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                String[] exceptions) {

            methods.add(new ParsedMethod(name, Type.getArgumentTypes(desc)));
            return null;
        }

        private ParsedType build() {
            return new ParsedType(name, superName, interfaceNames, methods);
        }
    }
}
