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
 * Generic parent class with type parameter U that extends GenericParentA<U>.
 * This creates a chain of generic inheritance.
 */
public abstract class GenericParentB<U> extends GenericParentA<U> {

    /**
     * Override the generic method from parent.
     * Type parameter T from GenericParentA is now bound to U.
     */
    @Override
    public String process(U value) {
        return "GenericParentB: " + super.process(value);
    }

    /**
     * New method specific to GenericParentB with generic type.
     */
    public U processSpecific(U value) {
        return value;
    }

    /**
     * Method with multiple parameters including generic type.
     */
    public String combine(U first, String second) {
        return first + " - " + second;
    }

    /**
     * Implementation of abstract method from GenericParentA.
     * The method signature is still generic at this level: validateAndProcess(U)
     * In ConcreteChild, this should be specialized to validateAndProcess(String)
     */
    @Override
    public U validateAndProcess(U input) {
        if (input == null) {
            return null;
        }
        // Basic validation and processing
        return input;
    }

    /**
     * Implementation of second abstract method from GenericParentA.
     * This also needs to be specialized in ConcreteChild.
     */
    @Override
    public U validateAndProcess2(U input) {
        if (input == null) {
            return null;
        }
        return input;
    }

    /**
     * Method that will NOT be overridden in ConcreteChild.
     * This method will have advice with subtype restriction to ConcreteChild,
     * causing it to appear in methodsThatOnlyNowFulfillAdvice.
     */
    public U specializedOnlyInChild(U value) {
        return value;
    }
}

