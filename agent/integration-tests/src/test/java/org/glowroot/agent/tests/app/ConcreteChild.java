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
package org.glowroot.agent.tests.app;

/**
 * Concrete (non-generic) class extending GenericParentB<String>.
 * All generic type parameters should be resolved to String.
 * This is the critical test case for generic specialization in weaving.
 */
public class ConcreteChild extends GenericParentB<String> {

    /**
     * Override generic method with concrete type String.
     * The weaver must correctly handle that U is now specialized to String.
     */
    @Override
    public String process(String value) {
        return "ConcreteChild: " + super.process(value);
    }

    /**
     * Override another method with specialized generic type.
     */
    @Override
    public String processSpecific(String value) {
        return "ConcreteChild-specific: " + value;
    }

    /**
     * Override method returning generic type (now String).
     */
    @Override
    public String getValue() {
        return "concrete-value";
    }

    /**
     * Override transform method with concrete type String.
     */
    @Override
    public String transform(String input) {
        return super.transform(input);
    }

    /**
     * Override abstract method with specialized signature.
     * CRITICAL: After weaving, this method should have signature validateAndProcess(String)
     * with NO generic type parameters. This tests that abstract method inheritance
     * through multiple generic levels is correctly specialized.
     */
    @Override
    public String validateAndProcess(String input) {
        if (input == null || input.isEmpty()) {
            return "ConcreteChild-validated: EMPTY";
        }
        return "ConcreteChild-validated: " + super.validateAndProcess(input);
    }

    /**
     * New method specific to concrete child.
     */
    public void execute() {
        process("test-value");
        processSpecific("specific-test");
        combine("first", "second");
        transform("transform-test");
        validateAndProcess("validation-test");
        // Call the method that has subtype-restricted advice
        // This method is NOT overridden, so it will appear in methodsThatOnlyNowFulfillAdvice
        specializedOnlyInChild("subtype-test");
    }

    @Override
    public String validateAndProcess2(String input) {
        if (input == null) {
            return null;
        }
        // Basic validation and processing
        return input;
    }

    // NOTE: specializedOnlyInChild is NOT overridden here!
    // It's inherited from GenericParentB and has advice with subTypeRestriction.
    // This is the KEY to populating methodsThatOnlyNowFulfillAdvice.
}

