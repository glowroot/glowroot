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
package org.glowroot.agent.tests;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.tests.app.NonGenericChildWithGenericMethods;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for method-level generic parameters in non-generic classes.
 *
 * Tests that methods with their own type parameters (like &lt;T&gt; T identity(T value))
 * are correctly handled during weaving, even when the class itself is not generic.
 * This is different from class-level generics where the entire class has type parameters.
 */
public class MethodLevelGenericWeavingIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    /**
     * Test that method-level generic parameters are preserved during weaving.
     * This tests the scenario where the method itself has generic type parameters
     * like &lt;T&gt; but the class is not generic.
     */
    @Test
    public void shouldWeaveMethodLevelGenericMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteMethodLevelGeneric.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    /**
     * Test that method-level generic methods maintain correct signatures after weaving.
     * This validates that methods like &lt;T&gt; T identity(T value) are not corrupted.
     */
    @Test
    public void shouldPreserveMethodLevelGenericSignatures() throws Exception {
        // when
        Trace trace = container.execute(VerifyMethodLevelGenericSignatures.class);

        // then - should complete without GenericSignatureFormatError
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");
    }

    /**
     * Test that aspect pointcuts correctly match method-level generic methods.
     * This verifies that methods with their own type parameters can be instrumented.
     */
    @Test
    public void shouldWeaveInheritedMethodLevelGenerics() throws Exception {
        // when
        Trace trace = container.execute(ExecuteMethodLevelGeneric.class);

        // then - verify trace entries for method-level generic methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");

        boolean foundIdentity = false;
        boolean foundProcessNumber = false;
        boolean foundFormatPair = false;
        boolean foundTransform = false;
        boolean foundFindMax = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("Method Generic Identity")) {
                foundIdentity = true;
            }
            if (message.contains("Method Generic ProcessNumber")) {
                foundProcessNumber = true;
            }
            if (message.contains("Method Generic FormatPair")) {
                foundFormatPair = true;
            }
            if (message.contains("Method Generic Transform")) {
                foundTransform = true;
            }
            if (message.contains("Method Generic FindMax")) {
                foundFindMax = true;
            }
        }

        assertThat(foundIdentity).as("Should find Identity trace entry").isTrue();
        assertThat(foundProcessNumber).as("Should find ProcessNumber trace entry").isTrue();
        assertThat(foundFormatPair).as("Should find FormatPair trace entry").isTrue();
        assertThat(foundTransform).as("Should find Transform trace entry").isTrue();
        assertThat(foundFindMax).as("Should find FindMax trace entry").isTrue();
    }

    /**
     * Test that generic method with single type parameter &lt;T&gt; is preserved.
     */
    @Test
    public void shouldPreserveSingleTypeParameterMethod() throws Exception {
        // when
        Trace trace = container.execute(TestSingleTypeParameter.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");
    }

    /**
     * Test that generic method with bounded type parameter &lt;T extends Number&gt; is preserved.
     */
    @Test
    public void shouldPreserveBoundedTypeParameterMethod() throws Exception {
        // when
        Trace trace = container.execute(TestBoundedTypeParameter.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");
    }

    /**
     * Test that generic method with multiple type parameters &lt;K, V&gt; is preserved.
     */
    @Test
    public void shouldPreserveMultipleTypeParametersMethod() throws Exception {
        // when
        Trace trace = container.execute(TestMultipleTypeParameters.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");
    }

    /**
     * Test that abstract generic method is correctly handled.
     */
    @Test
    public void shouldWeaveAbstractGenericMethod() throws Exception {
        // when
        Trace trace = container.execute(TestAbstractGenericMethod.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");

        boolean foundTransform = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Method Generic Transform")) {
                foundTransform = true;
                break;
            }
        }
        assertThat(foundTransform).as("Should find Transform trace entry").isTrue();
    }

    /**
     * Test that generic method with multiple bounds is preserved.
     */
    @Test
    public void shouldPreserveMultipleBoundsMethod() throws Exception {
        // when
        Trace trace = container.execute(TestMultipleBounds.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("MethodLevelGeneric Execute");

        boolean foundFindMax = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Method Generic FindMax")) {
                foundFindMax = true;
                break;
            }
        }
        assertThat(foundFindMax).as("Should find FindMax trace entry").isTrue();
    }

    // ========== AppUnderTest implementations ==========

    public static class ExecuteMethodLevelGeneric implements AppUnderTest {
        @Override
        public void executeApp() {
            new NonGenericChildWithGenericMethods().execute();
        }
    }

    public static class VerifyMethodLevelGenericSignatures implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            Class<?> clazz = NonGenericChildWithGenericMethods.class;

            // Test 1: Verify identity method has correct generic signature
            Method identityMethod = clazz.getMethod("identity", Object.class);
            assertThat(identityMethod).isNotNull();

            // Verify generic signature is preserved: <T> T identity(T)
            try {
                java.lang.reflect.TypeVariable<?>[] typeParams = identityMethod.getTypeParameters();
                assertThat(typeParams).hasSize(1);
                assertThat(typeParams[0].getName()).isEqualTo("T");

                java.lang.reflect.Type[] paramTypes = identityMethod.getGenericParameterTypes();
                assertThat(paramTypes).hasSize(1);
                // Parameter type should be the type variable T
                assertThat(paramTypes[0]).isInstanceOf(java.lang.reflect.TypeVariable.class);

                java.lang.reflect.Type returnType = identityMethod.getGenericReturnType();
                // Return type should also be the type variable T
                assertThat(returnType).isInstanceOf(java.lang.reflect.TypeVariable.class);
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in identity method - method-level generic signature corrupted", e);
            }

            // Test 2: Verify processNumber has bounded type parameter: <T extends Number>
            Method processNumberMethod = clazz.getMethod("processNumber", Number.class);
            assertThat(processNumberMethod).isNotNull();

            try {
                java.lang.reflect.TypeVariable<?>[] typeParams = processNumberMethod.getTypeParameters();
                assertThat(typeParams).hasSize(1);
                assertThat(typeParams[0].getName()).isEqualTo("T");

                // Verify the bound is Number
                java.lang.reflect.Type[] bounds = typeParams[0].getBounds();
                assertThat(bounds).hasSizeGreaterThanOrEqualTo(1);
                assertThat(bounds[0]).isEqualTo(Number.class);
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in processNumber - bounded type parameter corrupted", e);
            }

            // Test 3: Verify formatPair has two type parameters: <K, V>
            Method formatPairMethod = clazz.getMethod("formatPair", Object.class, Object.class);
            assertThat(formatPairMethod).isNotNull();

            try {
                java.lang.reflect.TypeVariable<?>[] typeParams = formatPairMethod.getTypeParameters();
                assertThat(typeParams).hasSize(2);
                assertThat(typeParams[0].getName()).isEqualTo("K");
                assertThat(typeParams[1].getName()).isEqualTo("V");
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in formatPair - multiple type parameters corrupted", e);
            }

            // Test 4: Verify transform abstract method signature
            Method transformMethod = clazz.getMethod("transform", Object.class);
            assertThat(transformMethod).isNotNull();

            try {
                java.lang.reflect.TypeVariable<?>[] typeParams = transformMethod.getTypeParameters();
                assertThat(typeParams).hasSize(1);
                assertThat(typeParams[0].getName()).isEqualTo("T");
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in transform - abstract generic method signature corrupted", e);
            }

            // Test 5: Verify findMax has multiple bounds: <T extends Number & Comparable<T>>
            Method findMaxMethod = clazz.getMethod("findMax", Number.class, Number.class);
            assertThat(findMaxMethod).isNotNull();

            try {
                java.lang.reflect.TypeVariable<?>[] typeParams = findMaxMethod.getTypeParameters();
                assertThat(typeParams).hasSize(1);
                assertThat(typeParams[0].getName()).isEqualTo("T");

                // Verify multiple bounds
                java.lang.reflect.Type[] bounds = typeParams[0].getBounds();
                assertThat(bounds.length).isGreaterThanOrEqualTo(2);
                // First bound should be Number
                assertThat(bounds[0]).isEqualTo(Number.class);
                // Second bound should be Comparable<T>
                assertThat(bounds[1].toString()).contains("Comparable");
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in findMax - multiple bounds corrupted", e);
            }

            // Test 6: Invoke generic methods via reflection to ensure they work
            String strResult = (String) identityMethod.invoke(instance, "test-string");
            assertThat(strResult).isEqualTo("test-string");

            Integer intResult = (Integer) identityMethod.invoke(instance, 42);
            assertThat(intResult).isEqualTo(42);

            double doubleResult = (Double) processNumberMethod.invoke(instance, 3.14);
            assertThat(doubleResult).isEqualTo(3.14);

            String pairResult = (String) formatPairMethod.invoke(instance, "key", 100);
            assertThat(pairResult).contains("key").contains("100");

            String transformResult = (String) transformMethod.invoke(instance, "transform-test");
            assertThat(transformResult).isEqualTo("transform-test");

            Integer maxResult = (Integer) findMaxMethod.invoke(instance, 10, 20);
            assertThat(maxResult).isEqualTo(20);
        }
    }

    public static class TestSingleTypeParameter implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            // Verify identity method can be called with different types
            String str = instance.identity("test");
            assertThat(str).isEqualTo("test");

            Integer num = instance.identity(42);
            assertThat(num).isEqualTo(42);

            Double dbl = instance.identity(3.14);
            assertThat(dbl).isEqualTo(3.14);
        }
    }

    public static class TestBoundedTypeParameter implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            // Test processNumber with different Number types
            double result1 = instance.processNumber(42);
            assertThat(result1).isEqualTo(42.0);

            double result2 = instance.processNumber(3.14);
            assertThat(result2).isEqualTo(3.14);

            double result3 = instance.processNumber(100L);
            assertThat(result3).isEqualTo(100.0);
        }
    }

    public static class TestMultipleTypeParameters implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            // Test formatPair with different type combinations
            String result1 = instance.formatPair("key1", "value1");
            assertThat(result1).isEqualTo("key1 : value1");

            String result2 = instance.formatPair("key2", 42);
            assertThat(result2).isEqualTo("key2 : 42");

            String result3 = instance.formatPair(100, 200);
            assertThat(result3).isEqualTo("100 : 200");
        }
    }

    public static class TestAbstractGenericMethod implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            // Test abstract transform method
            String strResult = instance.transform("test-transform");
            assertThat(strResult).isEqualTo("test-transform");

            Integer intResult = instance.transform(999);
            assertThat(intResult).isEqualTo(999);
        }
    }

    public static class TestMultipleBounds implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            NonGenericChildWithGenericMethods instance = new NonGenericChildWithGenericMethods();
            instance.execute();

            // Test findMax with different comparable Number types
            Integer maxInt = instance.findMax(10, 20);
            assertThat(maxInt).isEqualTo(20);

            Double maxDouble = instance.findMax(3.14, 2.71);
            assertThat(maxDouble).isEqualTo(3.14);

            Long maxLong = instance.findMax(100L, 50L);
            assertThat(maxLong).isEqualTo(100L);
        }
    }
}

