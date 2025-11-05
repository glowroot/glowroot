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
import org.glowroot.agent.tests.app.ConcreteChild;
import org.glowroot.agent.tests.app.GenericParentA;
import org.glowroot.agent.tests.app.GenericParentB;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for generic method specialization in weaving.
 *
 * Tests the fix for improper handling of generic specialization in
 * overrideAndWeaveInheritedMethod. This verifies that when a concrete
 * class extends generic parent classes, the method signatures are
 * correctly specialized and weaving doesn't generate invalid generic
 * methods in non-generic classes.
 */
public class GenericSpecializationWeavingIT {

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
     * Test that ConcreteChild can be instantiated and used after weaving.
     * This would fail with NullPointerException if generic signatures are
     * not properly specialized.
     */
    @Test
    public void shouldWeaveConcreteChildWithSpecializedGenerics() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteChild.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    /**
     * Test that the process method inherited from GenericParentA<T> is
     * correctly specialized to process(String) in ConcreteChild and can be woven.
     */
    @Test
    public void shouldWeaveSpecializedProcessMethod() throws Exception {
        // when - execute() starts transaction, process() is a trace entry
        Trace trace = container.execute(ExecuteConcreteChild.class);

        // then - verify process was called as a trace entry
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
        boolean foundProcess = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Concrete Process: test-value")) {
                foundProcess = true;
                break;
            }
        }
        assertThat(foundProcess).as("Should find Concrete Process trace entry").isTrue();
    }

    /**
     * Test that processSpecific method inherited from GenericParentB<U>
     * is correctly specialized to processSpecific(String) in ConcreteChild.
     */
    @Test
    public void shouldWeaveSpecializedProcessSpecificMethod() throws Exception {
        // when - execute() starts transaction, processSpecific() is a trace entry
        Trace trace = container.execute(ExecuteConcreteChild.class);

        // then - verify processSpecific was called as a trace entry
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
        boolean foundProcessSpecific = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Concrete ProcessSpecific: specific-test")) {
                foundProcessSpecific = true;
                break;
            }
        }
        assertThat(foundProcessSpecific).as("Should find Concrete ProcessSpecific trace entry").isTrue();
    }

    /**
     * Test that transform method from GenericParentA<T> works correctly
     * when specialized to transform(String) in ConcreteChild.
     */
    @Test
    public void shouldWeaveSpecializedTransformMethod() throws Exception {
        // when - execute() starts a transaction and calls transform internally
        Trace trace = container.execute(ExecuteConcreteChild.class);

        // then - verify transform was called as a trace entry
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
        boolean foundTransform = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Concrete Transform")) {
                foundTransform = true;
                assertThat(entry.getMessage()).contains("transform-test");
                break;
            }
        }
        assertThat(foundTransform).as("Should find Concrete Transform trace entry").isTrue();
    }

    /**
     * Test that abstract generic methods are correctly specialized.
     * CRITICAL: This tests that abstract method validateAndProcess(T) from GenericParentA
     * is correctly specialized to validateAndProcess(String) in ConcreteChild after weaving.
     */
    @Test
    public void shouldWeaveAbstractGenericMethod() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteChild.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
        boolean foundValidate = false;
        for (Trace.Entry entry : trace.getEntryList()) {
            if (entry.getMessage().contains("Concrete ValidateAndProcess: validation-test")) {
                foundValidate = true;
                break;
            }
        }
        assertThat(foundValidate).as("Should find abstract method trace entry").isTrue();
    }

    /**
     * Verify that reflection on ConcreteChild works correctly after weaving.
     * This is the critical test - if generic signatures aren't properly specialized,
     * getDeclaredMethod will throw NoSuchMethodException or return methods with
     * generic type parameters that shouldn't exist in the concrete class.
     */
    @Test
    public void shouldReflectCorrectMethodSignaturesAfterWeaving() throws Exception {
        // when - execute() starts a transaction, then we do reflection inside
        Trace trace = container.execute(ReflectOnConcreteChild.class);

        // then - should complete without NullPointerException or NoSuchMethodException
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
    }

    /**
     * Test the complete inheritance chain to ensure all levels are woven correctly.
     */
    @Test
    public void shouldWeaveEntireInheritanceChain() throws Exception {
        // when
        Trace trace = container.execute(CallMultipleMethodsOnConcreteChild.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");

        // Verify trace entries for all methods in the chain including abstract method
        boolean foundProcess = false;
        boolean foundProcessSpecific = false;
        boolean foundTransform = false;
        boolean foundValidateAndProcess = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("Concrete Process:")) {
                foundProcess = true;
            }
            if (message.contains("Concrete ProcessSpecific:")) {
                foundProcessSpecific = true;
            }
            if (message.contains("Concrete Transform:")) {
                foundTransform = true;
            }
            if (message.contains("Concrete ValidateAndProcess:")) {
                foundValidateAndProcess = true;
            }
        }

        assertThat(foundProcess).as("Should find Concrete Process trace entry").isTrue();
        assertThat(foundProcessSpecific).as("Should find Concrete ProcessSpecific trace entry").isTrue();
        assertThat(foundTransform).as("Should find Concrete Transform trace entry").isTrue();
        assertThat(foundValidateAndProcess).as("Should find Concrete ValidateAndProcess trace entry").isTrue();
    }

    /**
     * Test that bridge methods are correctly handled.
     * When a concrete class specializes generic methods, the compiler generates
     * bridge methods. The weaver must handle these correctly.
     */
    @Test
    public void shouldHandleBridgeMethodsCorrectly() throws Exception {
        // when
        Trace trace = container.execute(VerifyBridgeMethods.class);

        // then - should complete without errors
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
    }

    /**
     * COMPREHENSIVE REFLECTION TEST: Verify that after weaving, all method signatures
     * are correctly specialized and there are no malformed generic signatures.
     * This test directly validates the fix for GenericSignatureFormatError.
     */
    @Test
    public void shouldHaveCorrectMethodSignaturesAfterWeaving() throws Exception {
        // when
        Trace trace = container.execute(VerifyMethodSignaturesByReflection.class);

        // then - should complete without GenericSignatureFormatError or other reflection errors
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
    }

    /**
     * Test that generic type parameters are correctly resolved in method signatures.
     * This validates that type variables like T, U are replaced with concrete types like String.
     */
    @Test
    public void shouldResolveGenericTypeParametersCorrectly() throws Exception {
        // when
        Trace trace = container.execute(ValidateGenericTypeResolution.class);

        // then - should complete without signature parsing errors
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Concrete Execute");
    }

    // AppUnderTest implementations

    public static class ExecuteConcreteChild implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteChild().execute();
        }
    }

    public static class CallConcreteChildProcess implements AppUnderTest {
        @Override
        public void executeApp() {
            ConcreteChild child = new ConcreteChild();
            child.process("test-input");
        }
    }

    public static class CallConcreteChildProcessSpecific implements AppUnderTest {
        @Override
        public void executeApp() {
            ConcreteChild child = new ConcreteChild();
            child.processSpecific("specific-input");
        }
    }

    public static class CallConcreteChildTransform implements AppUnderTest {
        @Override
        public void executeApp() {
            ConcreteChild child = new ConcreteChild();
            child.transform("transform-input");
        }
    }

    public static class CallMultipleMethodsOnConcreteChild implements AppUnderTest {
        @Override
        public void executeApp() {
            ConcreteChild child = new ConcreteChild();
            child.execute(); // This internally calls process, processSpecific, combine, transform, validateAndProcess
        }
    }

    public static class ReflectOnConcreteChild implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ConcreteChild child = new ConcreteChild();

            // Execute first to start a transaction
            child.execute();

            // Try to get specialized methods using reflection
            // This would fail with NoSuchMethodException if signatures are wrong
            Method processMethod = ConcreteChild.class.getDeclaredMethod("process", String.class);
            assertThat(processMethod).isNotNull();
            assertThat(processMethod.getParameterTypes()[0]).isEqualTo(String.class);

            Method processSpecificMethod = ConcreteChild.class.getDeclaredMethod("processSpecific", String.class);
            assertThat(processSpecificMethod).isNotNull();
            assertThat(processSpecificMethod.getParameterTypes()[0]).isEqualTo(String.class);

            Method transformMethod = ConcreteChild.class.getMethod("transform", String.class);
            assertThat(transformMethod).isNotNull();

            // CRITICAL: Test abstract method specialization
            // This validates that abstract method validateAndProcess(T) from GenericParentA
            // is correctly specialized to validateAndProcess(String) in ConcreteChild
            Method validateMethod = ConcreteChild.class.getDeclaredMethod("validateAndProcess", String.class);
            assertThat(validateMethod).isNotNull();
            assertThat(validateMethod.getParameterTypes()[0]).isEqualTo(String.class);
            assertThat(validateMethod.getReturnType()).isEqualTo(String.class);

            // Invoke methods via reflection to ensure they work
            String result = (String) processMethod.invoke(child, "reflection-test");
            assertThat(result).contains("ConcreteChild");
            assertThat(result).contains("reflection-test");

            // Invoke abstract method via reflection - this is the CRITICAL test
            // If weaver generates wrong signature, this will throw NoSuchMethodException or NullPointerException
            String validateResult = (String) validateMethod.invoke(child, "abstract-test");
            assertThat(validateResult).contains("ConcreteChild-validated");
            assertThat(validateResult).contains("abstract-test");
        }
    }

    public static class VerifyBridgeMethods implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ConcreteChild child = new ConcreteChild();

            // Execute first to start a transaction
            child.execute();

            // Call through parent interface to ensure bridge methods work
            GenericParentA<String> parentA = child;
            String resultA = parentA.process("bridge-test-A");
            assertThat(resultA).contains("bridge-test-A");

            GenericParentB<String> parentB = child;
            String resultB = parentB.process("bridge-test-B");
            assertThat(resultB).contains("bridge-test-B");

            String resultSpecific = parentB.processSpecific("bridge-specific");
            assertThat(resultSpecific).contains("bridge-specific");

            // Test abstract method through parent interface - tests bridge method for abstract method
            String resultValidate = parentA.validateAndProcess("bridge-validate");
            assertThat(resultValidate).contains("ConcreteChild-validated");
            assertThat(resultValidate).contains("bridge-validate");

            // Verify methods can be invoked through Object reference
            Object obj = child;
            Method method = obj.getClass().getMethod("process", String.class);
            String resultObj = (String) method.invoke(obj, "object-ref-test");
            assertThat(resultObj).contains("object-ref-test");
        }
    }

    public static class VerifyMethodSignaturesByReflection implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ConcreteChild child = new ConcreteChild();
            child.execute(); // Start transaction

            Class<?> concreteChildClass = ConcreteChild.class;

            // Test 1: Verify process(String) method exists with correct signature
            Method processMethod = concreteChildClass.getDeclaredMethod("process", String.class);
            assertThat(processMethod).isNotNull();
            assertThat(processMethod.getParameterCount()).isEqualTo(1);
            assertThat(processMethod.getParameterTypes()[0]).isEqualTo(String.class);
            assertThat(processMethod.getReturnType()).isEqualTo(String.class);

            // Test 2: Verify processSpecific(String) method
            Method processSpecificMethod = concreteChildClass.getDeclaredMethod("processSpecific", String.class);
            assertThat(processSpecificMethod).isNotNull();
            assertThat(processSpecificMethod.getParameterTypes()[0]).isEqualTo(String.class);

            // Test 3: Verify transform(String) method
            Method transformMethod = concreteChildClass.getMethod("transform", String.class);
            assertThat(transformMethod).isNotNull();
            assertThat(transformMethod.getReturnType()).isEqualTo(String.class);

            // Test 4: CRITICAL - Verify validateAndProcess(String) abstract method specialization
            Method validateMethod = concreteChildClass.getDeclaredMethod("validateAndProcess", String.class);
            assertThat(validateMethod).isNotNull();
            assertThat(validateMethod.getParameterTypes()[0]).isEqualTo(String.class);
            assertThat(validateMethod.getReturnType()).isEqualTo(String.class);

            // Test 5: Verify specializedOnlyInChild(String) - inherited from GenericParentB<U>
            // This method is NOT overridden in ConcreteChild, it's inherited with specialized signature
            Method specializedOnlyMethod = concreteChildClass.getMethod("specializedOnlyInChild", String.class);
            assertThat(specializedOnlyMethod).isNotNull();
            assertThat(specializedOnlyMethod.getParameterTypes()[0]).isEqualTo(String.class);
            assertThat(specializedOnlyMethod.getReturnType()).isEqualTo(String.class);

            // Test 6: Verify no generic type parameters exist in method signatures
            // If weaving corrupted signatures, getGenericParameterTypes() would throw GenericSignatureFormatError
            try {
                java.lang.reflect.Type[] genericParamTypes = processMethod.getGenericParameterTypes();
                assertThat(genericParamTypes).hasSize(1);
                assertThat(genericParamTypes[0]).isEqualTo(String.class);

                java.lang.reflect.Type genericReturnType = processMethod.getGenericReturnType();
                assertThat(genericReturnType).isEqualTo(String.class);
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError detected - weaving corrupted method signature", e);
            }

            // Test 7: Verify validateAndProcess generic signature is not corrupted
            try {
                java.lang.reflect.Type[] validateGenericParams = validateMethod.getGenericParameterTypes();
                assertThat(validateGenericParams).hasSize(1);
                assertThat(validateGenericParams[0]).isEqualTo(String.class);

                java.lang.reflect.Type validateGenericReturn = validateMethod.getGenericReturnType();
                assertThat(validateGenericReturn).isEqualTo(String.class);
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in validateAndProcess - abstract method signature corrupted", e);
            }

            // Test 8: Verify specializedOnlyInChild signature is not corrupted
            try {
                java.lang.reflect.Type[] specializedGenericParams = specializedOnlyMethod.getGenericParameterTypes();
                assertThat(specializedGenericParams).hasSize(1);
                assertThat(specializedGenericParams[0]).isEqualTo(String.class);

                java.lang.reflect.Type specializedGenericReturn = specializedOnlyMethod.getGenericReturnType();
                assertThat(specializedGenericReturn).isEqualTo(String.class);
            } catch (java.lang.reflect.GenericSignatureFormatError e) {
                throw new AssertionError("GenericSignatureFormatError in specializedOnlyInChild - inherited method signature corrupted", e);
            }

            // Test 9: Invoke all methods via reflection to ensure they work correctly
            String processResult = (String) processMethod.invoke(child, "reflection-process");
            assertThat(processResult).contains("ConcreteChild").contains("reflection-process");

            String validateResult = (String) validateMethod.invoke(child, "reflection-validate");
            assertThat(validateResult).contains("ConcreteChild-validated").contains("reflection-validate");

            String specializedResult = (String) specializedOnlyMethod.invoke(child, "specialized-inherited");
            assertThat(specializedResult).contains("specialized-inherited");

            // Test 10: Verify getValue() method
            Method getValueMethod = concreteChildClass.getMethod("getValue");
            assertThat(getValueMethod.getReturnType()).isEqualTo(String.class);
            String valueResult = (String) getValueMethod.invoke(child);
            assertThat(valueResult).isEqualTo("concrete-value");
        }
    }

    public static class ValidateGenericTypeResolution implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            ConcreteChild child = new ConcreteChild();
            child.execute(); // Start transaction

            // Get all declared methods and verify none have unresolved type variables
            Method[] methods = ConcreteChild.class.getDeclaredMethods();

            for (Method method : methods) {
                // Skip synthetic and bridge methods
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }

                try {
                    // This will throw GenericSignatureFormatError if signature is corrupted
                    java.lang.reflect.Type[] paramTypes = method.getGenericParameterTypes();
                    java.lang.reflect.Type returnType = method.getGenericReturnType();

                    // Verify no type parameters contain malformed patterns like "Tjava.lang.Object"
                    for (java.lang.reflect.Type paramType : paramTypes) {
                        String typeName = paramType.toString();
                        assertThat(typeName)
                            .as("Parameter type in method " + method.getName() + " should not contain corrupted signature")
                            .doesNotContain("Tjava.lang.Object")
                            .doesNotContain(".lang.Objectontext");
                    }

                    String returnTypeName = returnType.toString();
                    assertThat(returnTypeName)
                        .as("Return type in method " + method.getName() + " should not contain corrupted signature")
                        .doesNotContain("Tjava.lang.Object")
                        .doesNotContain(".lang.Objectontext");

                } catch (java.lang.reflect.GenericSignatureFormatError e) {
                    throw new AssertionError("GenericSignatureFormatError in method: " + method.getName()
                        + " - This indicates weaving corrupted the method signature", e);
                } catch (java.lang.reflect.MalformedParameterizedTypeException e) {
                    throw new AssertionError("MalformedParameterizedTypeException in method: " + method.getName()
                        + " - This indicates incorrect type parameter resolution", e);
                }
            }

            // Also check inherited methods from GenericParentB
            Method[] inheritedMethods = ConcreteChild.class.getMethods();
            boolean found = false;
            for (Method method : inheritedMethods) {
                if (method.getName().equals("specializedOnlyInChild")) {
                    // This method is inherited from GenericParentB<U> and should be specialized to String
                    try {
                        if(method.getDeclaringClass() == ConcreteChild.class) {
                            found = true;
                            java.lang.reflect.Type[] paramTypes = method.getGenericParameterTypes();
                            assertThat(paramTypes).hasSize(1);
                            assertThat(paramTypes[0]).isEqualTo(String.class);

                            java.lang.reflect.Type returnType = method.getGenericReturnType();
                            assertThat(returnType).isEqualTo(String.class);
                        }
                    } catch (java.lang.reflect.GenericSignatureFormatError e) {
                        throw new AssertionError("GenericSignatureFormatError in inherited method specializedOnlyInChild"
                            + " - This indicates weaving corrupted the inherited method signature", e);
                    }
                }
            }
            assertThat(found).isEqualTo(true);

            // Test inherited methods are also correctly specialized
            GenericParentA<String> parentA = child;
            Method inheritedProcess = GenericParentA.class.getMethod("process", Object.class);

            // Call through parent interface - this tests bridge methods work
            String result = parentA.process("type-resolution-test");
            assertThat(result).contains("type-resolution-test");

            // Verify the specialized method can be invoked
            Method specializedProcess = ConcreteChild.class.getMethod("process", String.class);
            String specializedResult = (String) specializedProcess.invoke(child, "specialized-test");
            assertThat(specializedResult).contains("specialized-test");

            // Invoke specializedOnlyInChild through ConcreteChild instance
            String inheritedResult = child.specializedOnlyInChild("inherited-call");
            assertThat(inheritedResult).contains("inherited-call");
        }
    }
}

