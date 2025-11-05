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
 * Generic parent class with TWO type parameters T and R.
 * Tests generic specialization with multiple type parameters.
 */
public abstract class GenericParentTwoTypes<T, R> {

    /**
     * Method with first generic type parameter.
     */
    public String processFirst(T value) {
        return "GenericParentTwoTypes.processFirst: " + (value != null ? value.toString() : "null");
    }

    /**
     * Method with second generic type parameter.
     */
    public String processSecond(R value) {
        return "GenericParentTwoTypes.processSecond: " + (value != null ? value.toString() : "null");
    }

    /**
     * Method combining both type parameters.
     */
    public String processBoth(T first, R second) {
        return "Both: " + first + ", " + second;
    }

    /**
     * Method that takes first type and returns second type.
     */
    public abstract R transformFirstToSecond(T input);

    /**
     * Method that takes second type and returns first type.
     */
    public abstract T transformSecondToFirst(R input);

    /**
     * Method returning first generic type.
     */
    public T getFirstValue() {
        return null;
    }

    /**
     * Method returning second generic type.
     */
    public R getSecondValue() {
        return null;
    }
}

