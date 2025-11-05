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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A lightweight parser for JVM method descriptors and signatures.
 * This parser doesn't rely on ASM and implements the JVM specification (JVMS §4.3.3) directly.
 *
 * <p>Examples of method descriptors:
 * <ul>
 *   <li>{@code ()V} - no parameters, void return</li>
 *   <li>{@code (I)I} - int parameter, int return</li>
 *   <li>{@code (Ljava/lang/String;)V} - String parameter, void return</li>
 *   <li>{@code (TT;)Ljava/util/List;} - generic type parameter T, returns List</li>
 *   <li>{@code (Ljava/util/List<Ljava/lang/String;>;)V} - List with String type argument</li>
 * </ul>
 */
public class MethodDescriptorParser {

    private MethodDescriptorParser() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Parses parameter types from a method descriptor/signature.
     *
     * @param descriptor the method descriptor in JVM format (e.g., "(Ljava/lang/String;I)V")
     * @param typeMapping mapping of type variable names to concrete type names (can be empty)
     * @return list of resolved parameter type names in Java format (e.g., "java.lang.String", "int")
     */
    public static List<String> parseParameterTypes(String descriptor, Map<String, String> typeMapping) {
        if (descriptor == null) {
            return Collections.emptyList();
        }

        int startIdx = descriptor.indexOf('(');
        int endIdx = descriptor.indexOf(')');
        if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
            return Collections.emptyList();
        }

        String paramsDescriptor = descriptor.substring(startIdx + 1, endIdx);
        List<String> paramTypes = new ArrayList<>();

        int i = 0;
        while (i < paramsDescriptor.length()) {
            TypeParseResult result = parseType(paramsDescriptor, i, typeMapping);
            paramTypes.add(result.typeName);
            i = result.nextIndex;
        }

        return paramTypes;
    }

    /**
     * Parses the return type from a method descriptor/signature.
     *
     * @param descriptor the method descriptor in JVM format
     * @param typeMapping mapping of type variable names to concrete type names
     * @return the return type name in Java format, or "void" for void methods
     */
    public static String parseReturnType(String descriptor, Map<String, String> typeMapping) {
        if (descriptor == null) {
            return "void";
        }

        int endIdx = descriptor.indexOf(')');
        if (endIdx == -1 || endIdx >= descriptor.length() - 1) {
            return "void";
        }

        String returnDescriptor = descriptor.substring(endIdx + 1);
        TypeParseResult result = parseType(returnDescriptor, 0, typeMapping);
        return result.typeName;
    }

    /**
     * Parses a single type descriptor starting at the given index.
     */
    private static TypeParseResult parseType(String descriptor, int index, Map<String, String> typeMapping) {
        if (index >= descriptor.length()) {
            throw new IllegalArgumentException("Invalid descriptor: unexpected end at index " + index);
        }

        char typeCode = descriptor.charAt(index);

        switch (typeCode) {
            case 'L':
                return parseReferenceType(descriptor, index);
            case 'T':
                return parseTypeVariable(descriptor, index, typeMapping);
            case '[':
                return parseArrayType(descriptor, index, typeMapping);
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
            case 'J':
            case 'F':
            case 'D':
            case 'V':
                return parsePrimitiveType(typeCode, index);
            default:
                throw new IllegalArgumentException("Invalid type code: " + typeCode + " at index " + index);
        }
    }

    /**
     * Parses a reference type: Lpackage/Class;
     * Also handles generic types like: Ljava/util/List<Ljava/lang/String;>;
     */
    private static TypeParseResult parseReferenceType(String descriptor, int index) {
        // Need to properly find the end of this type descriptor
        // Handle generic type arguments: Ljava/util/List<Ljava/lang/String;>;
        int pos = index + 1; // skip 'L'
        int depth = 0;

        while (pos < descriptor.length()) {
            char c = descriptor.charAt(pos);
            if (c == '<') {
                depth++;
                pos++;
            } else if (c == '>') {
                depth--;
                pos++;
            } else if (c == ';') {
                if (depth == 0) {
                    // Found the end of the type descriptor
                    break;
                }
                pos++;
            } else {
                pos++;
            }
        }

        if (pos >= descriptor.length()) {
            throw new IllegalArgumentException("Invalid reference type: missing semicolon at index " + index);
        }

        // Extract internal name (e.g., "java/lang/String" or "java/util/List<Ljava/lang/String;>")
        String fullDescriptor = descriptor.substring(index + 1, pos);

        // Strip generic type arguments for the class name
        int genericStart = fullDescriptor.indexOf('<');
        String className;
        if (genericStart != -1) {
            className = fullDescriptor.substring(0, genericStart).replace('/', '.');
        } else {
            className = fullDescriptor.replace('/', '.');
        }

        return new TypeParseResult(className, pos + 1); // +1 to skip the semicolon
    }

    /**
     * Parses a type variable: TName;
     */
    private static TypeParseResult parseTypeVariable(String descriptor, int index, Map<String, String> typeMapping) {
        int semicolonIndex = descriptor.indexOf(';', index);
        if (semicolonIndex == -1) {
            throw new IllegalArgumentException("Invalid type variable: missing semicolon at index " + index);
        }

        String variableName = descriptor.substring(index + 1, semicolonIndex);
        String resolvedType = typeMapping.getOrDefault(variableName, "java.lang.Object");

        return new TypeParseResult(resolvedType, semicolonIndex + 1);
    }

    /**
     * Parses an array type: [elementType
     */
    private static TypeParseResult parseArrayType(String descriptor, int index, Map<String, String> typeMapping) {
        int dimensions = 0;
        int pos = index;

        // Count array dimensions
        while (pos < descriptor.length() && descriptor.charAt(pos) == '[') {
            dimensions++;
            pos++;
        }

        // Parse element type
        TypeParseResult elementResult = parseType(descriptor, pos, typeMapping);

        // Build array notation (e.g., "String[][]")
        StringBuilder arrayType = new StringBuilder(elementResult.typeName);
        for (int i = 0; i < dimensions; i++) {
            arrayType.append("[]");
        }

        return new TypeParseResult(arrayType.toString(), elementResult.nextIndex);
    }

    /**
     * Parses a primitive type (single character).
     */
    private static TypeParseResult parsePrimitiveType(char typeCode, int index) {
        String typeName;
        switch (typeCode) {
            case 'Z':
                typeName = Boolean.TYPE.getName();
                break;
            case 'B':
                typeName = Byte.TYPE.getName();
                break;
            case 'C':
                typeName = Character.TYPE.getName();
                break;
            case 'S':
                typeName = Short.TYPE.getName();
                break;
            case 'I':
                typeName = Integer.TYPE.getName();
                break;
            case 'J':
                typeName = Long.TYPE.getName();
                break;
            case 'F':
                typeName = Float.TYPE.getName();
                break;
            case 'D':
                typeName = Double.TYPE.getName();
                break;
            case 'V':
                typeName = Void.TYPE.getName();
                break;
            default:
                throw new IllegalArgumentException("Invalid primitive type code: " + typeCode);
        }
        return new TypeParseResult(typeName, index + 1);
    }

    /**
     * Result of parsing a type descriptor.
     */
    private static class TypeParseResult {
        final String typeName;
        final int nextIndex;

        TypeParseResult(String typeName, int nextIndex) {
            this.typeName = typeName;
            this.nextIndex = nextIndex;
        }
    }
}

