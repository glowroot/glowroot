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
 * Concrete class extending GenericMiddleTwoTypes<String>.
 * All generic parameters fully specialized: T = String, R = Integer.
 * Tests complete specialization through multi-level inheritance.
 */
public class ConcreteTwoTypes extends GenericMiddleTwoTypes<String> {

    @Override
    public String processFirst(String value) {
        return "ConcreteTwoTypes.processFirst: " + value;
    }

    @Override
    public String transformSecondToFirst(Integer input) {
        if (input == null) {
            return "null";
        }
        return "String-" + input;
    }

    @Override
    public String validateInput(String input) {
        if (input == null || input.isEmpty()) {
            return "INVALID";
        }
        return "VALID: " + input;
    }

    @Override
    public String getFirstValue() {
        return "concrete-first";
    }

    @Override
    public Integer getSecondValue() {
        return 42;
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        processFirst("test");
        processSecond(100);
        processBoth("hello", 5);
        transformFirstToSecond("world");
        transformSecondToFirst(99);
        processWithLength("testing");
        validateInput("validation-test");
    }
}

