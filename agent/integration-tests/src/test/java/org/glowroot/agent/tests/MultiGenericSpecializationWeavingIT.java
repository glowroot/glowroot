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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.tests.app.ConcreteTwoTypes;
import org.glowroot.agent.tests.app.ConcreteThreeTypes;
import org.glowroot.agent.tests.app.ConcreteBounded;
import org.glowroot.agent.tests.app.ConcreteMultipleBounds;
import org.glowroot.agent.tests.app.ConcreteNested;
import org.glowroot.agent.tests.app.ConcreteKeyValue;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for multi-generic type specialization in weaving.
 *
 * Tests that the weaver correctly handles generic specialization when:
 * - Aspects are defined on parent generic classes with Object parameter types
 * - Concrete classes inherit and specialize these methods with concrete types
 * - The weaver must use GenericTypeResolver to match generic signatures
 */
public class MultiGenericSpecializationWeavingIT {

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

    // ========== TWO TYPE PARAMETERS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithTwoTypeParameters() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteTwoTypes.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteTwoTypes Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeTwoTypeParameterMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteTwoTypes.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteTwoTypes Execute");

        boolean foundProcessFirst = false;
        boolean foundProcessSecond = false;
        boolean foundTransformSecondToFirst = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("Multi Generic ProcessFirst")) {
                foundProcessFirst = true;
            }
            if (message.contains("Multi Generic ProcessSecond")) {
                foundProcessSecond = true;
            }
            if (message.contains("TransformSecondToFirst")) {
                foundTransformSecondToFirst = true;
            }
        }

        assertThat(foundProcessFirst).as("Should find ProcessFirst trace entry").isTrue();
        assertThat(foundProcessSecond).as("Should find ProcessSecond trace entry").isTrue();
        assertThat(foundTransformSecondToFirst).as("Should find TransformSecondToFirst trace entry").isTrue();
    }

    // ========== THREE TYPE PARAMETERS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithThreeTypeParameters() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteThreeTypes.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteThreeTypes Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeThreeTypeParameterMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteThreeTypes.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteThreeTypes Execute");

        boolean foundProcessFirst = false;
        boolean foundProcessSecond = false;
        boolean foundProcessThird = false;
        boolean foundTransformTtoR = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("ConcreteThreeTypes ProcessFirst")) {
                foundProcessFirst = true;
            }
            if (message.contains("ConcreteThreeTypes ProcessSecond")) {
                foundProcessSecond = true;
            }
            if (message.contains("ProcessThird")) {
                // Can be "Multi Generic ProcessThird" or "Middle Generic ProcessThird"
                foundProcessThird = true;
            }
            if (message.contains("TransformTtoR")) {
                foundTransformTtoR = true;
            }
        }

        assertThat(foundProcessFirst).as("Should find ProcessFirst trace entry").isTrue();
        assertThat(foundProcessSecond).as("Should find ProcessSecond trace entry").isTrue();
        assertThat(foundProcessThird).as("Should find ProcessThird trace entry").isTrue();
        assertThat(foundTransformTtoR).as("Should find TransformTtoR trace entry").isTrue();
    }

    // ========== BOUNDED TYPE PARAMETERS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithBoundedTypeParameter() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteBounded.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteBounded Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeBoundedTypeParameterMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteBounded.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteBounded Execute");

        boolean foundProcessNumber = false;
        boolean foundMultiplyByTwo = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("ConcreteBounded ProcessNumber")) {
                foundProcessNumber = true;
            }
            if (message.contains("MultiplyByTwo")) {
                foundMultiplyByTwo = true;
            }
        }

        assertThat(foundProcessNumber).as("Should find ProcessNumber trace entry").isTrue();
        assertThat(foundMultiplyByTwo).as("Should find MultiplyByTwo trace entry").isTrue();
    }

    // ========== MULTIPLE BOUNDS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithMultipleBounds() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteMultipleBounds.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteMultipleBounds Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeMultipleBoundsMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteMultipleBounds.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteMultipleBounds Execute");

        boolean foundCompareValues = false;
        boolean foundFindMax = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("CompareValues")) {
                // Aspect produces "CompareValues: X, Y" not "ConcreteMultipleBounds CompareValues"
                foundCompareValues = true;
            }
            if (message.contains("FindMax")) {
                foundFindMax = true;
            }
        }

        assertThat(foundCompareValues).as("Should find CompareValues trace entry").isTrue();
        assertThat(foundFindMax).as("Should find FindMax trace entry").isTrue();
    }

    // ========== NESTED GENERICS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithNestedGenerics() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteNested.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteNested Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeNestedGenericMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteNested.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteNested Execute");

        boolean foundProcessList = false;
        boolean foundFilterList = false;
        boolean foundFindInList = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("ConcreteNested ProcessList")) {
                foundProcessList = true;
            }
            if (message.contains("FilterList")) {
                foundFilterList = true;
            }
            if (message.contains("FindInList")) {
                foundFindInList = true;
            }
        }

        assertThat(foundProcessList).as("Should find ProcessList trace entry").isTrue();
        assertThat(foundFilterList).as("Should find FilterList trace entry").isTrue();
        assertThat(foundFindInList).as("Should find FindInList trace entry").isTrue();
    }

    // ========== KEY-VALUE GENERICS TESTS ==========

    @Test
    public void shouldWeaveConcreteWithKeyValueGenerics() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteKeyValue.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteKeyValue Execute");
        assertThat(trace.getEntryCount()).isGreaterThan(0);
    }

    @Test
    public void shouldSpecializeKeyValueMethods() throws Exception {
        // when
        Trace trace = container.execute(ExecuteConcreteKeyValue.class);

        // then - verify trace entries exist for specialized methods
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("ConcreteKeyValue Execute");

        boolean foundGet = false;
        boolean foundPut = false;
        boolean foundProcessEntry = false;

        for (Trace.Entry entry : trace.getEntryList()) {
            String message = entry.getMessage();
            if (message.contains("Get:") || message.contains("ConcreteKeyValue Get")) {
                // Aspect produces "Get: key" not "ConcreteKeyValue Get"
                foundGet = true;
            }
            if (message.contains("Put:") || message.contains("ConcreteKeyValue Put")) {
                // Aspect produces "Put: key = value" not "ConcreteKeyValue Put"
                foundPut = true;
            }
            if (message.contains("ProcessEntry")) {
                foundProcessEntry = true;
            }
        }

        assertThat(foundGet).as("Should find Get trace entry").isTrue();
        assertThat(foundPut).as("Should find Put trace entry").isTrue();
        assertThat(foundProcessEntry).as("Should find ProcessEntry trace entry").isTrue();
    }

    // ========== AppUnderTest implementations ==========

    public static class ExecuteConcreteTwoTypes implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteTwoTypes().execute();
        }
    }

    public static class ExecuteConcreteThreeTypes implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteThreeTypes().execute();
        }
    }

    public static class ExecuteConcreteBounded implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteBounded().execute();
        }
    }

    public static class ExecuteConcreteMultipleBounds implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteMultipleBounds().execute();
        }
    }

    public static class ExecuteConcreteNested implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteNested().execute();
        }
    }

    public static class ExecuteConcreteKeyValue implements AppUnderTest {
        @Override
        public void executeApp() {
            new ConcreteKeyValue().execute();
        }
    }
}
