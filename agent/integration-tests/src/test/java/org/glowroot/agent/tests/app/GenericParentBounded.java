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
 * Generic parent class with bounded type parameter T extends Number.
 * Tests generic specialization with type bounds.
 */
public abstract class GenericParentBounded<T extends Number> {

    /**
     * Method with bounded generic type parameter.
     */
    public String processNumber(T value) {
        if (value == null) {
            return "null";
        }
        return "Number: " + value.doubleValue();
    }

    /**
     * Method that uses Number-specific operations.
     */
    public double calculateDouble(T value) {
        if (value == null) {
            return 0.0;
        }
        return value.doubleValue() * 2;
    }

    /**
     * Abstract method with bounded return type.
     */
    public abstract T multiplyByTwo(T input);

    /**
     * Method comparing two values.
     */
    public String compare(T first, T second) {
        if (first == null || second == null) {
            return "null comparison";
        }
        int result = Double.compare(first.doubleValue(), second.doubleValue());
        return result > 0 ? "first > second" : result < 0 ? "first < second" : "equal";
    }

    public T getValue() {
        return null;
    }
}

