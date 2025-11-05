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
 * Concrete class extending GenericParentKeyValue<String, Integer>.
 * Tests specialization with Map-like key-value structures.
 */
public class ConcreteKeyValue extends GenericParentKeyValue<String, Integer> {

    @Override
    public String processEntry(String key, Integer value) {
        if (key == null || value == null) {
            return "null entry";
        }
        return "Entry: " + key + " = " + value;
    }

    @Override
    public Integer transformValue(String key, Integer value) {
        if (value == null) {
            return 0;
        }
        if (key == null) {
            return value;
        }
        return value * key.length();
    }

    /**
     * Additional method specific to String-Integer mapping.
     */
    public String formatEntry(String key, Integer value) {
        return String.format("[%s: %d]", key, value);
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        put("first", 1);
        put("second", 2);
        put("third", 3);
        get("first");
        listKeys();
        listValues();
        processEntry("test", 42);
        transformValue("key", 10);
        containsKey("first");
        size();
        formatEntry("example", 100);
    }
}

