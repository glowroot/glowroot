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

import java.util.List;

/**
 * Non-generic child class that inherits generic methods from parent.
 * Neither the parent nor child class are generic, but the methods are.
 * Tests that method-level generics are preserved during weaving.
 */
public class NonGenericChildWithGenericMethods extends NonGenericParentWithGenericMethods {

    /**
     * Implement the abstract generic method.
     */
    @Override
    public <T> T transform(T input) {
        // Simply return the input, but log it
        return input;
    }

    /**
     * Override a generic method (optional override).
     */
    @Override
    public <T> T identity(T value) {
        return super.identity(value);
    }

    /**
     * Execute method to test all inherited generic methods.
     */
    public void execute() {
        // Test identity with String
        String str = identity("test-string");

        // Test identity with Integer
        Integer num = identity(42);

        // Test processNumber
        double result = processNumber(3.14);

        // Test formatPair
        String pair = formatPair("key", 100);

        // Test createList
        List<String> list = createList("a", "b", "c");

        // Test findFirst
        String first = findFirst(list);

        // Test transform
        String transformed = transform("transform-me");

        // Test findMax
        Integer max = findMax(10, 20);
    }
}

