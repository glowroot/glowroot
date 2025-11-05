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
 * Generic parent class with nested generic types.
 * Tests specialization with complex nested generics like List<T>.
 */
public abstract class GenericParentNested<T> {

    /**
     * Method processing a list of generic type.
     */
    public String processList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return "empty list";
        }
        return "List size: " + values.size();
    }

    /**
     * Method returning list of generic type.
     */
    public List<T> createList(T... items) {
        List<T> list = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                list.add(item);
            }
        }
        return list;
    }

    /**
     * Abstract method with nested generics.
     */
    public abstract List<T> filterList(List<T> input);

    /**
     * Method with multiple nested generic parameters.
     */
    public abstract T findInList(List<T> list, T target);

    /**
     * Method transforming list.
     */
    public List<T> reverseList(List<T> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        List<T> result = new ArrayList<>();
        for (int i = input.size() - 1; i >= 0; i--) {
            result.add(input.get(i));
        }
        return result;
    }
}

