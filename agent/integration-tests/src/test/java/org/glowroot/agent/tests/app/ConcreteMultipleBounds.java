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
 * Concrete class extending GenericParentMultipleBounds<Integer>.
 * Tests specialization with multiple bounds (Integer extends Number & Comparable<Integer>).
 */
public class ConcreteMultipleBounds extends GenericParentMultipleBounds<Integer> {

    @Override
    public Integer findMax(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    @Override
    public boolean isInRange(Integer value, Integer min, Integer max) {
        if (value == null || min == null || max == null) {
            return false;
        }
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /**
     * Additional method specific to Integer.
     */
    public String formatInteger(Integer value) {
        if (value == null) {
            return "null";
        }
        return "Integer: " + value;
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        toDouble(42);
        compareValues(10, 20);
        findMax(5, 15);
        isInRange(10, 5, 15);
        processComparable(100);
        formatInteger(999);
    }
}

