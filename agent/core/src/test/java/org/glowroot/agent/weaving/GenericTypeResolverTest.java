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
import org.objectweb.asm.Type;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class GenericTypeResolverTest {

    //Byte code parsing
    @Test
    public void shouldParseMethodSignatureWithTypeParameters() {
        String signature = "<T:Ljava/lang/Object;>(TT;)TT;";
        GenericClassVisitor.GenericClassInfo result = GenericTypeResolver.parseMethodSignature(signature);

        assertThat(result).isNotNull();
        assertThat(result.typeParameters()).hasSize(1);
        assertThat(result.typeParameters().get(0).name()).isEqualTo("T");
    }

    @Test
    public void shouldReturnNullForNullSignature() {
        GenericClassVisitor.GenericClassInfo result = GenericTypeResolver.parseMethodSignature(null);
        assertThat(result).isNotNull();
        assertThat(result.typeParameters()).isEmpty();
    }

    @Test
    public void shouldResolveSimpleTypeVariableInSignature() {
        String signature = "(TE;)V";
        ThinClassVisitor.ThinClass thinClass = createMockThinClass("E", "java/lang/String");
        AnalyzedClass superClass = createMockAnalyzedClass("E");

        String resolved = GenericTypeResolver.resolveMethodSignature(signature, thinClass, superClass);

        assertThat(resolved).isEqualTo("(Ljava/lang/String;)V");
    }

    @Test
    public void shouldResolveMultipleTypeVariablesInSignature() {
        String signature = "(TK;TV;)V";
        Map<String, String> typeMapping = new LinkedHashMap<>();
        typeMapping.put("K", "java/lang/String");
        typeMapping.put("V", "java/lang/Integer");

        ThinClassVisitor.ThinClass thinClass = createMockThinClassMultiple(typeMapping);
        AnalyzedClass superClass = createMockAnalyzedClassMultiple(Arrays.asList("K", "V"));

        String resolved = GenericTypeResolver.resolveMethodSignature(signature, thinClass, superClass);

        assertThat(resolved).isEqualTo("(Ljava/lang/String;Ljava/lang/Integer;)V");
    }

    @Test
    public void shouldHandleMethodWithReturnType() {
        String signature = "(TE;)TE;";
        ThinClassVisitor.ThinClass thinClass = createMockThinClass("E", "java/lang/String");
        AnalyzedClass superClass = createMockAnalyzedClass("E");

        String resolved = GenericTypeResolver.resolveMethodSignature(signature, thinClass, superClass);

        assertThat(resolved).isEqualTo("(Ljava/lang/String;)Ljava/lang/String;");
    }

    @Test
    public void shouldReturnOriginalSignatureWhenNoTypeMapping() {
        String signature = "(Ljava/lang/String;)V";
        ThinClassVisitor.ThinClass thinClass = createMockThinClassWithoutGenerics();
        AnalyzedClass superClass = createMockAnalyzedClassWithoutGenerics();

        String resolved = GenericTypeResolver.resolveMethodSignature(signature, thinClass, superClass);

        assertThat(resolved).isEqualTo(signature);
    }

    @Test
    public void shouldReturnNullForNullMethodSignature() {
        String resolved = GenericTypeResolver.resolveMethodSignature(null, null, null);
        assertThat(resolved).isNull();
    }

    @Test
    public void shouldResolveParameterTypes() {
        AnalyzedMethod method = createMockMethod(Arrays.asList("TE;", "TU;"));
        Map<String, String> typeMapping = new LinkedHashMap<>();
        typeMapping.put("E", "java/lang/String");
        typeMapping.put("U", "java/lang/Integer");

        ThinClassVisitor.ThinClass thinClass = createMockThinClassMultiple(typeMapping);
        AnalyzedClass superClass = createMockAnalyzedClassMultiple(Arrays.asList("E", "U"));

        List<String> resolved = GenericTypeResolver.resolveParameterTypes(method, thinClass, superClass);

        assertThat(resolved).containsExactly("java/lang/String", "java/lang/Integer");
    }

    @Test
    public void shouldResolveReturnType() {
        AnalyzedMethod method = createMockMethodWithReturnType("TE;");
        ThinClassVisitor.ThinClass thinClass = createMockThinClass("E", "java/lang/String");
        AnalyzedClass superClass = createMockAnalyzedClass("E");

        String resolved = GenericTypeResolver.resolveReturnType(method, thinClass, superClass);

        assertThat(resolved).isEqualTo("java/lang/String");
    }

    @Test
    public void shouldHandleComplexGenericSignature() {
        String signature = "(TT;)Ljava/util/List;";
        ThinClassVisitor.ThinClass thinClass = createMockThinClass("T", "java/lang/String");
        AnalyzedClass superClass = createMockAnalyzedClass("T");

        String resolved = GenericTypeResolver.resolveMethodSignature(signature, thinClass, superClass);

        assertThat(resolved).isEqualTo("(Ljava/lang/String;)Ljava/util/List;");
    }

    @Test
    public void shouldResolveParameterTypesWithoutSignature() {
        AnalyzedMethod method = ImmutableAnalyzedMethod.builder()
                .modifiers(0)
                .name("testMethod")
                .addParameterTypes("Ljava/lang/String;", "I")
                .returnType("V")
                .build();

        ThinClassVisitor.ThinClass thinClass = createMockThinClassWithoutGenerics();
        AnalyzedClass superClass = createMockAnalyzedClassWithoutGenerics();

        List<String> resolved = GenericTypeResolver.resolveParameterTypes(method, thinClass, superClass);

        assertThat(resolved).containsExactly("Ljava/lang/String;", "I");
    }

    @Test
    public void shouldResolveReturnTypeWithoutSignature() {
        AnalyzedMethod method = ImmutableAnalyzedMethod.builder()
                .modifiers(0)
                .name("testMethod")
                .returnType("Ljava/lang/String;")
                .build();

        ThinClassVisitor.ThinClass thinClass = createMockThinClassWithoutGenerics();
        AnalyzedClass superClass = createMockAnalyzedClassWithoutGenerics();

        String resolved = GenericTypeResolver.resolveReturnType(method, thinClass, superClass);

        assertThat(resolved).isEqualTo("Ljava/lang/String;");
    }

    // Helper methods

    private ThinClassVisitor.ThinClass createMockThinClass(String typeParam, String concreteType) {
        Map<String, String> typeMapping = new HashMap<>();
        typeMapping.put(typeParam, concreteType);
        return createMockThinClassMultiple(typeMapping);
    }

    private ThinClassVisitor.ThinClass createMockThinClassMultiple(final Map<String, String> typeMapping) {
        final GenericClassVisitor.GenericClassInfo genericInfo = new GenericClassVisitor.GenericClassInfo() {
            @Override
            public List<GenericClassVisitor.TypeParameterInfo> typeParameters() {
                return Collections.emptyList();
            }

            @Override
            public GenericClassVisitor.GenericTypeInfo superClass() {
                final List<GenericClassVisitor.GenericTypeInfo> args = new ArrayList<>();
                for (final Map.Entry<String, String> entry : typeMapping.entrySet()) {
                    args.add(new GenericClassVisitor.GenericTypeInfo() {
                        @Override
                        public String rawType() {
                            return entry.getValue();
                        }

                        @Override
                        public List<GenericClassVisitor.GenericTypeInfo> arguments() {
                            return Collections.emptyList();
                        }

                        @Override
                        public boolean isTypeVariable() {
                            return false;
                        }

                        @Override
                        public String variableName() {
                            return null;
                        }
                    });
                }

                return new GenericClassVisitor.GenericTypeInfo() {
                    @Override
                    public String rawType() {
                        return "test/GenericParent";
                    }

                    @Override
                    public List<GenericClassVisitor.GenericTypeInfo> arguments() {
                        return args;
                    }

                    @Override
                    public boolean isTypeVariable() {
                        return false;
                    }

                    @Override
                    public String variableName() {
                        return null;
                    }
                };
            }

            @Override
            public List<GenericClassVisitor.GenericTypeInfo> interfaces() {
                return Collections.emptyList();
            }
        };

        return new ThinClassVisitor.ThinClass() {
            @Override
            public String name() {
                return "test/ConcreteClass";
            }

            @Override
            public String superName() {
                return "test/GenericParent";
            }

            @Override
            public List<Type> ejbRemoteInterfaces() {
                return Collections.emptyList();
            }

            @Override
            public List<ThinClassVisitor.ThinMethod> bridgeMethods() {
                return Collections.emptyList();
            }

            @Override
            public List<String> interfaces() {
                return Collections.emptyList();
            }

            @Override
            public List<ThinClassVisitor.ThinMethod> nonBridgeMethods() {
                return Collections.emptyList();
            }

            @Override
            public int access() {
                return 0;
            }

            @Override
            public List<String> annotations() {
                return Collections.emptyList();
            }

            @Override
            public GenericClassVisitor.GenericClassInfo genericClassInfo() {
                return genericInfo;
            }
        };
    }

    private ThinClassVisitor.ThinClass createMockThinClassWithoutGenerics() {
        final GenericClassVisitor.GenericClassInfo emptyInfo = GenericClassVisitor.GenericClassInfo.EMPTY;

        return new ThinClassVisitor.ThinClass() {
            @Override
            public String name() {
                return "test/ConcreteClass";
            }

            @Override
            public String superName() {
                return "java/lang/Object";
            }

            @Override
            public List<Type> ejbRemoteInterfaces() {
                return Collections.emptyList();
            }

            @Override
            public List<ThinClassVisitor.ThinMethod> bridgeMethods() {
                return Collections.emptyList();
            }

            @Override
            public List<String> interfaces() {
                return Collections.emptyList();
            }

            @Override
            public List<ThinClassVisitor.ThinMethod> nonBridgeMethods() {
                return Collections.emptyList();
            }

            @Override
            public int access() {
                return 0;
            }

            @Override
            public List<String> annotations() {
                return Collections.emptyList();
            }

            @Override
            public GenericClassVisitor.GenericClassInfo genericClassInfo() {
                return emptyInfo;
            }
        };
    }

    private AnalyzedClass createMockAnalyzedClass(String typeParam) {
        return createMockAnalyzedClassMultiple(Collections.singletonList(typeParam));
    }

    private AnalyzedClass createMockAnalyzedClassMultiple(final List<String> typeParams) {
        GenericClassVisitor.GenericClassInfo genericInfo = new GenericClassVisitor.GenericClassInfo() {
            @Override
            public List<GenericClassVisitor.TypeParameterInfo> typeParameters() {
                List<GenericClassVisitor.TypeParameterInfo> params = new ArrayList<>();
                for (final String param : typeParams) {
                    params.add(new GenericClassVisitor.TypeParameterInfo() {
                        @Override
                        public String name() {
                            return param;
                        }

                        @Override
                        public List<GenericClassVisitor.GenericTypeInfo> bounds() {
                            return Collections.emptyList();
                        }
                    });
                }
                return params;
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

        return ImmutableAnalyzedClass.builder()
                .modifiers(0)
                .name("test/GenericParent")
                .superName("java/lang/Object")
                .genericClassInfo(genericInfo)
                .ejbRemote(false)
                .build();
    }

    private AnalyzedClass createMockAnalyzedClassWithoutGenerics() {
        GenericClassVisitor.GenericClassInfo emptyInfo = GenericClassVisitor.GenericClassInfo.EMPTY;

        return ImmutableAnalyzedClass.builder()
                .modifiers(0)
                .name("test/ConcreteClass")
                .superName("java/lang/Object")
                .genericClassInfo(emptyInfo)
                .ejbRemote(false)
                .build();
    }

    private AnalyzedMethod createMockMethod(List<String> paramTypes) {
        // Build signature from parameter types: (TE;TU;)V
        StringBuilder sig = new StringBuilder("(");
        for (String paramType : paramTypes) {
            sig.append(paramType);
        }
        sig.append(")V");

        return ImmutableAnalyzedMethod.builder()
                .modifiers(0)
                .name("testMethod")
                .parameterTypes(paramTypes)
                .returnType("V")
                .signature(sig.toString())
                .build();
    }

    private AnalyzedMethod createMockMethodWithReturnType(String returnType) {
        // Build signature with return type: ()TE;
        String sig = "()" + returnType;

        return ImmutableAnalyzedMethod.builder()
                .modifiers(0)
                .name("testMethod")
                .parameterTypes(Collections.<String>emptyList())
                .returnType(returnType)
                .signature(sig)
                .build();
    }
}
