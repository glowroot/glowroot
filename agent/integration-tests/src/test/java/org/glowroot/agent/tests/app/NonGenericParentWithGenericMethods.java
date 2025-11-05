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
import java.util.ArrayList;

/**
 * Non-generic parent class with generic methods.
 * Tests that methods with their own type parameters (like &lt;T&gt;) are correctly handled
 * during weaving, even when the class itself is not generic.
 */
public abstract class NonGenericParentWithGenericMethods {

    /**
     * Generic method with type parameter T.
     * The method itself is generic, but the class is not.
     */
    public <T> T identity(T value) {
        return value;
    }

    /**
     * Generic method with bounded type parameter.
     */
    public <T extends Number> double processNumber(T value) {
        if (value == null) {
            return 0.0;
        }
        return value.doubleValue();
    }

    /**
     * Generic method with multiple type parameters.
     */
    public <K, V> String formatPair(K key, V value) {
        return key + " : " + value;
    }

    /**
     * Generic method returning a list.
     */
    public <T> List<T> createList(T... items) {
        List<T> list = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                list.add(item);
            }
        }
        return list;
    }

    /**
     * Generic method with wildcard parameter.
     */
    public <T> T findFirst(List<? extends T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /**
     * Abstract generic method to be implemented by child.
     */
    public abstract <T> T transform(T input);

    /**
     * Generic method with multiple bounds.
     */
    public <T extends Number & Comparable<T>> T findMax(T first, T second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }
}

