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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.ASM9;

class GenericClassVisitor extends SignatureVisitor {

    GenericClassVisitor() {
        super(ASM9);
    }

    private final List<TypeParameterInfo> typeParameters = new ArrayList<>();
    private final List<GenericTypeInfo> interfaces = new ArrayList<>();
    private ImmutableTypeParameterInfo.Builder currentParamBuilder;
    private @Nullable GenericTypeInfo superClass;



    @Value.Immutable
    interface GenericClassInfo {
        List<TypeParameterInfo> typeParameters();
        @Nullable
        GenericTypeInfo superClass();
        List<GenericTypeInfo> interfaces();

        GenericClassInfo EMPTY = new GenericClassInfo() {
            @Override
            public List<GenericClassVisitor.TypeParameterInfo> typeParameters() {
                return Collections.emptyList();
            }

            @Override
            public GenericClassVisitor.GenericTypeInfo superClass() {
                return null;
            }

            @Override
            public List<GenericClassVisitor.GenericTypeInfo> interfaces() {
                return Collections.emptyList();
            }
        };
    }

    @Value.Immutable
    interface TypeParameterInfo {
        String name();
        List<GenericTypeInfo> bounds();
    }

    @Value.Immutable
    interface GenericTypeInfo {
        String rawType();
        List<GenericTypeInfo> arguments();
        boolean isTypeVariable();
        String variableName();
    }

    @Override
    public void visitFormalTypeParameter(String name) {
        if (currentParamBuilder != null) {
            typeParameters.add(currentParamBuilder.build());
        }
        currentParamBuilder = ImmutableTypeParameterInfo.builder().name(name);
    }

    @Override
    public SignatureVisitor visitClassBound() {
        return new GenericTypeVisitor(typeInfo -> {
            if (currentParamBuilder != null) {
                currentParamBuilder.addBounds(typeInfo);
            }
        });
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
        return new GenericTypeVisitor(typeInfo -> {
            if (currentParamBuilder != null) {
                currentParamBuilder.addBounds(typeInfo);
            }
        });
    }

    @Override
    public SignatureVisitor visitSuperclass() {
        if (currentParamBuilder != null) {
            typeParameters.add(currentParamBuilder.build());
            currentParamBuilder = null;
        }
        return new GenericTypeVisitor(typeInfo -> superClass = typeInfo);
    }

    @Override
    public SignatureVisitor visitInterface() {
        return new GenericTypeVisitor(typeInfo -> interfaces.add(typeInfo));
    }

    @Nullable
    GenericClassInfo getGenericClassInfo() {
        if (currentParamBuilder != null) {
            typeParameters.add(currentParamBuilder.build());
            currentParamBuilder = null;
        }
        return ImmutableGenericClassInfo.builder()
                .addAllTypeParameters(typeParameters)
                .superClass(superClass)
                .addAllInterfaces(interfaces)
                .build();
    }

    private class GenericTypeVisitor extends SignatureVisitor {
        private final Consumer<GenericTypeInfo> consumer;
        private final List<GenericTypeInfo> arguments = new ArrayList<>();
        private @Nullable String rawType;
        private boolean isTypeVariable = false;
        private @Nullable String variableName;

        GenericTypeVisitor(Consumer<GenericTypeInfo> consumer) {
            super(ASM9);
            this.consumer = consumer;
        }

        @Override
        public void visitClassType(String name) {
            rawType = name.replace('/', '.');
        }

        @Override
        public void visitTypeArgument() {
            // Wildcard, not handled
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new GenericTypeVisitor(typeInfo -> arguments.add(typeInfo));
        }

        @Override
        public void visitTypeVariable(String name) {
            isTypeVariable = true;
            variableName = name;
        }

        @Override
        public void visitEnd() {
            ImmutableGenericTypeInfo.Builder builder = ImmutableGenericTypeInfo.builder()
                    .addAllArguments(arguments)
                    .isTypeVariable(isTypeVariable)
                    .variableName(variableName != null ? variableName : "")
                    .rawType(rawType != null ? rawType : "");
            consumer.accept(builder.build());
        }
    }
}
