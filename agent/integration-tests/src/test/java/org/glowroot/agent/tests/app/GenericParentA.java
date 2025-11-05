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
 * Generic parent class with type parameter T.
 * This class will be extended by GenericParentB with another generic type.
 */
public abstract class GenericParentA<T> {

    /**
     * Generic method that takes type parameter T.
     * This method should be properly specialized in concrete subclasses.
     */
    public String process(T value) {
        return "GenericParentA: " + (value != null ? value.toString() : "null");
    }

    /**
     * Another generic method for testing complex scenarios.
     */
    public T transform(T input) {
        return input;
    }

    /**
     * Method returning generic type.
     */
    public T getValue() {
        return null;
    }

    /**
     * Abstract method with generic parameter and return type.
     * CRITICAL TEST: This method must be specialized in concrete subclasses.
     * After weaving, ConcreteChild should have validateAndProcess(String)
     * without any generic type parameters in the bytecode.
     */
    public abstract T validateAndProcess(T input);

    /**
     * Second abstract method to increase test coverage of generic specialization.
     * This ensures overrideAndWeaveInheritedMethod is called multiple times.
     */
    public abstract T validateAndProcess2(T input);
}

