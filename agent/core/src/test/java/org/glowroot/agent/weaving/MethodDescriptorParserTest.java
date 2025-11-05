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

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MethodDescriptorParser}.
 */
public class MethodDescriptorParserTest {

    @Test
    public void shouldParseVoidReturnType() {
        String descriptor = "()V";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("void");
    }

    @Test
    public void shouldParsePrimitiveReturnTypes() {
        assertThat(MethodDescriptorParser.parseReturnType("()Z", Collections.emptyMap()))
                .isEqualTo("boolean");
        assertThat(MethodDescriptorParser.parseReturnType("()B", Collections.emptyMap()))
                .isEqualTo("byte");
        assertThat(MethodDescriptorParser.parseReturnType("()C", Collections.emptyMap()))
                .isEqualTo("char");
        assertThat(MethodDescriptorParser.parseReturnType("()S", Collections.emptyMap()))
                .isEqualTo("short");
        assertThat(MethodDescriptorParser.parseReturnType("()I", Collections.emptyMap()))
                .isEqualTo("int");
        assertThat(MethodDescriptorParser.parseReturnType("()J", Collections.emptyMap()))
                .isEqualTo("long");
        assertThat(MethodDescriptorParser.parseReturnType("()F", Collections.emptyMap()))
                .isEqualTo("float");
        assertThat(MethodDescriptorParser.parseReturnType("()D", Collections.emptyMap()))
                .isEqualTo("double");
    }

    @Test
    public void shouldParseObjectReturnType() {
        String descriptor = "()Ljava/lang/String;";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.lang.String");
    }

    @Test
    public void shouldParseArrayReturnType() {
        String descriptor = "()[Ljava/lang/String;";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.lang.String[]");
    }

    @Test
    public void shouldParseMultiDimensionalArrayReturnType() {
        String descriptor = "()[[I";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("int[][]");
    }

    @Test
    public void shouldParseTypeVariableReturnType() {
        String descriptor = "(TT;)TT;";
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put("T", "java.lang.String");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, typeMapping);
        assertThat(returnType).isEqualTo("java.lang.String");
    }

    @Test
    public void shouldParseTypeVariableReturnTypeWithDefaultMapping() {
        String descriptor = "(TU;)TU;";
        // No type mapping provided
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.lang.Object");
    }

    @Test
    public void shouldParseGenericReturnTypeWithTypeArguments() {
        String descriptor = "()Ljava/util/List<Ljava/lang/String;>;";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.util.List");
    }

    @Test
    public void shouldParseEmptyParameterList() {
        String descriptor = "()V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).isEmpty();
    }

    @Test
    public void shouldParseSinglePrimitiveParameter() {
        String descriptor = "(I)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("int");
    }

    @Test
    public void shouldParseMultiplePrimitiveParameters() {
        String descriptor = "(IJZ)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("int", "long", "boolean");
    }

    @Test
    public void shouldParseSingleObjectParameter() {
        String descriptor = "(Ljava/lang/String;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.lang.String");
    }

    @Test
    public void shouldParseMultipleObjectParameters() {
        String descriptor = "(Ljava/lang/String;Ljava/lang/Integer;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.lang.String", "java.lang.Integer");
    }

    @Test
    public void shouldParseMixedParameters() {
        String descriptor = "(ILjava/lang/String;JZ)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("int", "java.lang.String", "long", "boolean");
    }

    @Test
    public void shouldParseArrayParameters() {
        String descriptor = "([Ljava/lang/String;[I)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.lang.String[]", "int[]");
    }

    @Test
    public void shouldParseTypeVariableParameters() {
        String descriptor = "(TT;TU;)V";
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put("T", "java.lang.String");
        typeMapping.put("U", "java.lang.Integer");

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, typeMapping);
        assertThat(paramTypes).containsExactly("java.lang.String", "java.lang.Integer");
    }

    @Test
    public void shouldParseGenericParametersWithTypeArguments() {
        String descriptor = "(Ljava/util/List<Ljava/lang/String;>;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.util.List");
    }

    @Test
    public void shouldParseComplexGenericParameters() {
        String descriptor = "(Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.util.Map");
    }

    @Test
    public void shouldParseNestedGenericParameters() {
        String descriptor = "(Ljava/util/List<Ljava/util/List<Ljava/lang/String;>;>;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.util.List");
    }

    @Test
    public void shouldHandleNullDescriptorForParameterTypes() {
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(null, Collections.emptyMap());
        assertThat(paramTypes).isEmpty();
    }

    @Test
    public void shouldHandleNullDescriptorForReturnType() {
        String returnType = MethodDescriptorParser.parseReturnType(null, Collections.emptyMap());
        assertThat(returnType).isEqualTo("void");
    }

    @Test
    public void shouldHandleInvalidDescriptorMissingParentheses() {
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes("V", Collections.emptyMap());
        assertThat(paramTypes).isEmpty();
    }

    @Test
    public void shouldHandleDescriptorWithOnlyOpenParenthesis() {
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes("(", Collections.emptyMap());
        assertThat(paramTypes).isEmpty();
    }

    @Test
    public void shouldThrowExceptionForInvalidTypeCode() {
        String descriptor = "(X)V"; // X is not a valid type code
        assertThatThrownBy(() -> MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid type code: X");
    }

    @Test
    public void shouldThrowExceptionForMissingSemicolonInReferenceType() {
        String descriptor = "(Ljava/lang/String)V"; // Missing semicolon
        assertThatThrownBy(() -> MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing semicolon");
    }

    @Test
    public void shouldThrowExceptionForMissingSemicolonInTypeVariable() {
        String descriptor = "(TT)V"; // Missing semicolon
        assertThatThrownBy(() -> MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing semicolon");
    }

    @Test
    public void shouldParseComplexRealWorldMethod() {
        // public String process(String value, int count, List<String> items)
        String descriptor = "(Ljava/lang/String;ILjava/util/List<Ljava/lang/String;>;)Ljava/lang/String;";

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("java.lang.String", "int", "java.util.List");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.lang.String");
    }

    @Test
    public void shouldParseGenericMethodWithTypeVariable() {
        // public <T> T transform(T input)
        String descriptor = "(TT;)TT;";
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put("T", "java.lang.String");

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, typeMapping);
        assertThat(paramTypes).containsExactly("java.lang.String");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, typeMapping);
        assertThat(returnType).isEqualTo("java.lang.String");
    }

    @Test
    public void shouldParseGenericMethodWithMultipleTypeVariables() {
        // public <T, U> U process(T first, U second)
        String descriptor = "(TT;TU;)TU;";
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put("T", "java.lang.String");
        typeMapping.put("U", "java.lang.Integer");

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, typeMapping);
        assertThat(paramTypes).containsExactly("java.lang.String", "java.lang.Integer");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, typeMapping);
        assertThat(returnType).isEqualTo("java.lang.Integer");
    }

    @Test
    public void shouldHandleDescriptorWithNoReturnType() {
        String descriptor = "()";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("void");
    }

    @Test
    public void shouldParseArrayOfPrimitives() {
        String descriptor = "([I[J[Z)[[D";

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("int[]", "long[]", "boolean[]");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("double[][]");
    }

    @Test
    public void shouldParseThreeDimensionalArray() {
        String descriptor = "()[[[Ljava/lang/String;";
        String returnType = MethodDescriptorParser.parseReturnType(descriptor, Collections.emptyMap());
        assertThat(returnType).isEqualTo("java.lang.String[][][]");
    }

    @Test
    public void shouldParseInnerClassType() {
        String descriptor = "(Lorg/example/Outer$Inner;)V";
        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, Collections.emptyMap());
        assertThat(paramTypes).containsExactly("org.example.Outer$Inner");
    }

    @Test
    public void shouldHandlePartialTypeMapping() {
        // Some type variables are mapped, others are not
        String descriptor = "(TT;TU;TV;)TT;";
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put("T", "java.lang.String");
        // U and V are not mapped, should default to Object

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, typeMapping);
        assertThat(paramTypes).containsExactly("java.lang.String", "java.lang.Object", "java.lang.Object");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, typeMapping);
        assertThat(returnType).isEqualTo("java.lang.String");
    }

    @Test
    public void shouldParseEmptyTypeMapping() {
        String descriptor = "(TT;)TT;";
        Map<String, String> emptyMapping = Collections.emptyMap();

        List<String> paramTypes = MethodDescriptorParser.parseParameterTypes(descriptor, emptyMapping);
        assertThat(paramTypes).containsExactly("java.lang.Object");

        String returnType = MethodDescriptorParser.parseReturnType(descriptor, emptyMapping);
        assertThat(returnType).isEqualTo("java.lang.Object");
    }
}

