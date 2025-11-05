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
import java.util.stream.Collectors;

/**
 * Concrete class extending GenericParentNested<String>.
 * Tests specialization with nested generics List<T> -> List<String>.
 */
public class ConcreteNested extends GenericParentNested<String> {

    @Override
    public String processList(List<String> values) {
        String result = super.processList(values);
        return "ConcreteNested: " + result;
    }

    @Override
    public List<String> filterList(List<String> input) {
        if (input == null) {
            return new ArrayList<>();
        }
        return input.stream()
            .filter(s -> s != null && !s.isEmpty())
            .collect(Collectors.toList());
    }

    @Override
    public String findInList(List<String> list, String target) {
        if (list == null || target == null) {
            return null;
        }
        for (String item : list) {
            if (target.equals(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Additional method specific to String lists.
     */
    public String joinList(List<String> list, String delimiter) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(delimiter, list);
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        List<String> list = createList("a", "b", "c");
        processList(list);
        filterList(list);
        findInList(list, "b");
        reverseList(list);
        joinList(list, ", ");
    }
}

