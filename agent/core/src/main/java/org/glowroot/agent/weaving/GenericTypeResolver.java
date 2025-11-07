/*
 * Copyright 2025 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.objectweb.asm.signature.SignatureReader;

import java.util.*;

/**
 * A utility class that inspects compiled bytecode using ASM to determine generic type parameters.
 * This design replaces reflection-based resolution to eliminate ClassLoader dependencies and improve runtime safety.
 *
 * <p>The resolver works by:
 * <ul>
 *   <li>Parsing generic signatures from bytecode using ASM's signature API</li>
 *   <li>Building type mappings between generic type parameters and their concrete implementations</li>
 *   <li>Resolving method signatures, parameter types, and return types in the context of class hierarchies</li>
 * </ul>
 */
public class GenericTypeResolver {

    private GenericTypeResolver() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Parses a method signature string to extract generic type information.
     * <p>
     * This method uses ASM's signature parsing capabilities to analyze method signatures
     * in JVM internal format and extract information about generic type parameters,
     * return types, and parameter types.
     *
     * @param signature the method signature in JVM internal format (e.g.,
     *                  {@code "<T:Ljava/lang/Object;>(TT;)Ljava/util/List<TT;>;"}).
     *                  This is typically obtained from the method's signature attribute
     *                  in the class file. Can be {@code null} for non-generic methods.
     * @return a {@link GenericClassVisitor.GenericClassInfo} object containing parsed
     *         generic type information including type parameters, parameter types, and
     *         return type. Returns {@code null} if the signature parameter is {@code null}.
     * @see GenericClassVisitor
     * @see SignatureReader
     */
    public static GenericClassVisitor.GenericClassInfo parseMethodSignature(String signature) {
        if (signature != null) {
            GenericClassVisitor visitor = new GenericClassVisitor();
            SignatureReader sr = new SignatureReader(signature);
            sr.accept(visitor);
            return visitor.getGenericClassInfo();
        } else {
            return GenericClassVisitor.GenericClassInfo.EMPTY;
        }
    }

    /**
     * Resolves a method signature by substituting generic type variables with their concrete types.
     * <p>
     * This method analyzes the type hierarchy to determine how generic type parameters in a superclass
     * are specialized in a subclass, then rewrites the method signature accordingly.
     *
     * @param analyzedMethodSignature the original method signature containing type variables
     * @param analyzedThinClass the thin class representation containing generic type information
     * @param superAnalyzedClass the analyzed superclass that defines the generic type parameters
     * @return the resolved method signature with type variables replaced by concrete types,
     *         or the original signature if no resolution is needed. Returns {@code null} if
     *         the input signature is {@code null}.
     */
    public static String resolveMethodSignature(String analyzedMethodSignature,
                                                ThinClassVisitor.ThinClass analyzedThinClass,
                                                AnalyzedClass superAnalyzedClass) {
        if (analyzedMethodSignature == null) {
            return null;
        }

        Map<String, String> typeMapping = buildTypeMapping(analyzedThinClass, superAnalyzedClass);

        if (typeMapping.isEmpty()) {
            return analyzedMethodSignature;
        }

        // Manually replace type variables in the signature string
        String resolvedSignature = analyzedMethodSignature;
        for (Map.Entry<String, String> entry : typeMapping.entrySet()) {
            String typeVar = "T" + entry.getKey() + ";";
            String replacement = "L" + entry.getValue().replace('.', '/') + ";";
            resolvedSignature = resolvedSignature.replace(typeVar, replacement);
        }

        return resolvedSignature;
    }

    /**
     * Resolves parameter types for a method by substituting generic type variables with concrete types.
     * <p>
     * For each parameter type in the method, this method checks if it's a generic type variable
     * and replaces it with the corresponding concrete type based on the class hierarchy.
     *
     * @param analyzedMethod the method whose parameter types should be resolved
     * @param analyzedThinClass the thin class representation containing generic type information
     * @param superAnalyzedClass the analyzed superclass that defines the generic type parameters
     * @return a list of resolved parameter types with type variables replaced by concrete types.
     *         Returns the original parameter types if no resolution is needed or possible.
     */
    public static List<String> resolveParameterTypes(AnalyzedMethod analyzedMethod,
                                                     ThinClassVisitor.ThinClass analyzedThinClass,
                                                     AnalyzedClass superAnalyzedClass) {
        String signature = analyzedMethod.signature();
        if (signature == null) {
            return analyzedMethod.parameterTypes();
        }

        Map<String, String> typeMapping = buildTypeMapping(analyzedThinClass, superAnalyzedClass);
        if (typeMapping.isEmpty()) {
            return analyzedMethod.parameterTypes();
        }

        return MethodDescriptorParser.parseParameterTypes(signature, typeMapping);
    }


    /**
     * Resolves the return type for a method by substituting generic type variables with concrete types.
     * <p>
     * If the return type is a generic type variable, this method replaces it with the corresponding
     * concrete type based on the class hierarchy.
     *
     * @param analyzedMethod the method whose return type should be resolved
     * @param analyzedThinClass the thin class representation containing generic type information
     * @param superAnalyzedClass the analyzed superclass that defines the generic type parameters
     * @return the resolved return type with type variables replaced by concrete types.
     *         Returns the original return type if no resolution is needed or possible.
     *         Returns {@code null} if the method has no return type.
     */
    public static String resolveReturnType(AnalyzedMethod analyzedMethod,
                                           ThinClassVisitor.ThinClass analyzedThinClass,
                                           AnalyzedClass superAnalyzedClass) {
        String signature = analyzedMethod.signature();
        if (signature == null) {
            return analyzedMethod.returnType();
        }

        Map<String, String> typeMapping = buildTypeMapping(analyzedThinClass, superAnalyzedClass);

        if (typeMapping.isEmpty()) {
            return analyzedMethod.returnType();
        }

        return MethodDescriptorParser.parseReturnType(signature, typeMapping);
    }

    private static Map<String, String> buildTypeMapping(ThinClassVisitor.ThinClass analyzedThinClass,
                                                        AnalyzedClass superAnalyzedClass) {
        Map<String, String> typeMapping = new LinkedHashMap<>();  // Changed from HashMap

        if (analyzedThinClass == null || superAnalyzedClass == null) {
            return typeMapping;
        }

        GenericClassVisitor.GenericClassInfo classInfo = analyzedThinClass.genericClassInfo();
        if (classInfo == null || classInfo.superClass() == null) {
            return typeMapping;
        }

        List<String> superTypeParams = extractTypeParameters(superAnalyzedClass);
        List<GenericClassVisitor.GenericTypeInfo> superTypeArgs = classInfo.superClass().arguments();

        for (int i = 0; i < Math.min(superTypeParams.size(), superTypeArgs.size()); i++) {
            String typeParam = superTypeParams.get(i);
            GenericClassVisitor.GenericTypeInfo typeArg = superTypeArgs.get(i);

            if (!typeArg.isTypeVariable()) {
                typeMapping.put(typeParam, typeArg.rawType());
            }
        }

        return typeMapping;
    }

    private static List<String> extractTypeParameters(AnalyzedClass analyzedClass) {
        List<String> typeParams = new ArrayList<>();

        GenericClassVisitor.GenericClassInfo classInfo = analyzedClass.genericClassInfo();
        if (classInfo != null) {
            for (GenericClassVisitor.TypeParameterInfo paramInfo : classInfo.typeParameters()) {
                typeParams.add(paramInfo.name());
            }
        }

        return typeParams;
    }


    /**
     * Extracts generic class information from a loaded Class object using reflection.
     * This is used in Plan C scenarios where bytecode is not available.
     *
     * @param clazz the loaded class to extract generic information from
     * @return GenericClassInfo containing type parameters, superclass, and interfaces information
     */
    public static GenericClassVisitor.GenericClassInfo getGenericClassInfo(Class<?> clazz) {
        // TODO manage generics when is required (I hope don't need it)
        return GenericClassVisitor.GenericClassInfo.EMPTY;
    }
}

