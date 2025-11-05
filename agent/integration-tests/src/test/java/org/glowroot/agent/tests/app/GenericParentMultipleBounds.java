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
 * Generic parent with multiple bounds: T extends Number & Comparable<T>.
 * Tests specialization with complex bounded type parameters.
 */
public abstract class GenericParentMultipleBounds<T extends Number & Comparable<T>> {

    /**
     * Method using Number operations.
     */
    public double toDouble(T value) {
        if (value == null) {
            return 0.0;
        }
        return value.doubleValue();
    }

    /**
     * Method using Comparable operations.
     */
    public String compareValues(T first, T second) {
        if (first == null || second == null) {
            return "null comparison";
        }
        int result = first.compareTo(second);
        return result > 0 ? "first > second" : result < 0 ? "first < second" : "equal";
    }

    /**
     * Abstract method combining both bounds.
     */
    public abstract T findMax(T first, T second);

    /**
     * Abstract method with multiple parameters.
     */
    public abstract boolean isInRange(T value, T min, T max);

    /**
     * Method using both interfaces.
     */
    public String processComparable(T value) {
        return "Value: " + value + ", Double: " + value.doubleValue();
    }
}

