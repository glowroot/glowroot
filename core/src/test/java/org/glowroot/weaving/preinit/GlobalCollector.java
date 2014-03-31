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
package org.glowroot.weaving.preinit;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.weaving.TypeNames;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class GlobalCollector {

    private static final Logger logger = LoggerFactory.getLogger(GlobalCollector.class);

    // caches
    private final Set<ReferencedMethod> referencedMethods = Sets.newHashSet();
    private final Map<String, Optional<TypeCollector>> typeCollectors = Maps.newHashMap();

    private final Set<ReferencedMethod> overrides = Sets.newTreeSet();

    private String indent = "";

    public void processMethodFailIfNotFound(ReferencedMethod rootMethod) throws IOException {
        processMethod(rootMethod, true);
    }

    public void processMethod(ReferencedMethod rootMethod) throws IOException {
        String prevIndent = indent;
        indent = indent + "  ";
        processMethod(rootMethod, false);
        indent = prevIndent;
    }

    public void registerType(String typeName) throws IOException {
        addType(typeName);
    }

    public void processOverrides() throws IOException {
        while (true) {
            for (String typeName : typeCollectors.keySet()) {
                addOverrideReferencedMethods(typeName);
                addOverrideBootstrapMethods(typeName);
            }
            if (overrides.isEmpty()) {
                return;
            }
            for (ReferencedMethod override : overrides) {
                logger.debug("{} (processing overrides)", override);
                processMethod(override);
            }
            overrides.clear();
        }
    }

    public List<String> usedTypes() {
        List<String> typeNames = Lists.newArrayList();
        for (String typeName : Sets.newTreeSet(typeCollectors.keySet())) {
            if (!Types.inBootstrapClassLoader(typeName) && !typeName.startsWith("org/slf4j/")
                    && !typeName.startsWith("org/glowroot/shaded/slf4j/")
                    && Types.exists(typeName)) {
                typeNames.add(TypeNames.fromInternal(typeName));
            }
        }
        return typeNames;
    }

    private void processMethod(ReferencedMethod rootMethod, boolean expected) throws IOException {
        if (referencedMethods.contains(rootMethod)) {
            return;
        }
        String owner = rootMethod.getOwner();
        if (owner.startsWith("[")) {
            // method on an Array, e.g. new String[] {}.clone()
            return;
        }
        logger.debug("{}{}", indent, rootMethod);
        // add the containing type and its super types if not already added
        Optional<TypeCollector> optional = typeCollectors.get(owner);
        if (optional == null) {
            optional = addType(owner);
        }
        if (!optional.isPresent()) {
            // couldn't find type
            if (expected) {
                throw new IOException("Could not find type '" + owner + "'");
            } else {
                return;
            }
        }
        referencedMethods.add(rootMethod);
        if (Types.inBootstrapClassLoader(owner)) {
            return;
        }
        if (owner.startsWith("org/slf4j/") || owner.startsWith("org/glowroot/shaded/slf4j/")) {
            return;
        }
        TypeCollector typeCollector = optional.get();
        String methodId = rootMethod.getName() + ":" + rootMethod.getDesc();
        MethodCollector methodCollector = typeCollector.getMethodCollector(methodId);
        if (expected && methodCollector == null) {
            throw new IOException("Could not find method '" + rootMethod + "'");
        }
        if (methodCollector == null && !rootMethod.getName().equals("<clinit>")
                && typeCollector.getSuperType() != null) {
            // can't find method in type, so go up to super type
            processMethod(ReferencedMethod.from(typeCollector.getSuperType(), methodId));
        }
        // methodCollector can be null, e.g. unimplemented interface method in an abstract class
        if (methodCollector != null) {
            processMethod(methodCollector);
        }
    }

    private void processMethod(MethodCollector methodCollector) throws IOException {
        // add types referenced from inside the method
        for (String referencedType : methodCollector.getReferencedTypes()) {
            addType(referencedType);
        }
        // recurse into other methods called from inside the method
        for (ReferencedMethod referencedMethod : methodCollector.getReferencedMethods()) {
            processMethod(referencedMethod);
        }
    }

    private Optional<TypeCollector> addType(String typeName) throws IOException {
        Optional<TypeCollector> optional = typeCollectors.get(typeName);
        if (optional != null) {
            return optional;
        }
        List<String> allSuperTypes = Lists.newArrayList();
        TypeCollector typeCollector = createTypeCollector(typeName);
        if (typeCollector == null) {
            optional = Optional.absent();
            typeCollectors.put(typeName, optional);
            return optional;
        }
        // don't return or recurse without typeCollector being fully built
        typeCollectors.put(typeName, Optional.of(typeCollector));
        if (typeCollector.getSuperType() != null) {
            // it's a major problem if super type is not present, ok to call Optional.get()
            TypeCollector superTypeCollector = addType(typeCollector.getSuperType()).get();
            allSuperTypes.addAll(superTypeCollector.getAllSuperTypes());
            allSuperTypes.add(typeCollector.getSuperType());
        }
        for (String interfaceType : typeCollector.getInterfaceTypes()) {
            Optional<TypeCollector> itype = addType(interfaceType);
            if (itype.isPresent()) {
                allSuperTypes.addAll(itype.get().getAllSuperTypes());
                allSuperTypes.add(interfaceType);
            } else {
                logger.debug("could not find type: {}", interfaceType);
                typeCollector.setAllSuperTypes(allSuperTypes);
                return Optional.absent();
            }
        }
        typeCollector.setAllSuperTypes(allSuperTypes);
        // add static initializer (if it exists)
        processMethod(ReferencedMethod.from(typeName, "<clinit>", "()V"));
        // always add default constructor (if it exists)
        processMethod(ReferencedMethod.from(typeName, "<init>", "()V"));
        return Optional.of(typeCollector);
    }

    @Nullable
    private TypeCollector createTypeCollector(String typeName) {
        if (ClassLoader.getSystemResource(typeName + ".class") == null) {
            logger.error("could not find class: {}", typeName);
            return null;
        }
        TypeCollector typeCollector = new TypeCollector();
        try {
            ClassReader cr = new ClassReader(typeName);
            MyRemappingClassAdapter visitor = new MyRemappingClassAdapter(typeCollector);
            cr.accept(visitor, 0);
            return typeCollector;
        } catch (IOException e) {
            logger.error("error parsing class: {}", typeName);
            return null;
        }
    }

    private void addOverrideReferencedMethods(String typeName) {
        Optional<TypeCollector> optional = typeCollectors.get(typeName);
        if (!optional.isPresent()) {
            return;
        }
        TypeCollector typeCollector = optional.get();
        for (String methodId : typeCollector.getMethodIds()) {
            if (methodId.startsWith("<clinit>:") || methodId.startsWith("<init>:")) {
                continue;
            }
            for (String superType : typeCollector.getAllSuperTypes()) {
                if (referencedMethods.contains(ReferencedMethod.from(superType, methodId))) {
                    addOverrideMethod(typeName, methodId);
                    // break inner loop
                    break;
                }
            }
        }
    }

    private void addOverrideBootstrapMethods(String typeName) {
        if (Types.inBootstrapClassLoader(typeName)) {
            return;
        }
        Optional<TypeCollector> optional = typeCollectors.get(typeName);
        if (!optional.isPresent()) {
            return;
        }
        TypeCollector typeCollector = optional.get();
        // add overridden bootstrap methods in type, e.g. hashCode(), toString()
        for (String methodId : typeCollector.getMethodIds()) {
            if (methodId.startsWith("<clinit>:") || methodId.startsWith("<init>:")) {
                continue;
            }
            for (String superType : typeCollector.getAllSuperTypes()) {
                if (Types.inBootstrapClassLoader(superType)) {
                    TypeCollector superTypeCollector = typeCollectors.get(superType).get();
                    if (superTypeCollector.getMethodCollector(methodId) != null) {
                        addOverrideMethod(typeName, methodId);
                    }
                }
            }
        }
    }

    private void addOverrideMethod(String typeName, String methodId) {
        ReferencedMethod referencedMethod = ReferencedMethod.from(typeName, methodId);
        if (!referencedMethods.contains(referencedMethod)) {
            overrides.add(referencedMethod);
        }
    }
}
