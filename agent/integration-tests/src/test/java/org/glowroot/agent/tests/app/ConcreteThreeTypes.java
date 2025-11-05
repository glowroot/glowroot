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
 * Concrete class extending GenericMiddleThreeTypes<String, Integer>.
 * All generic parameters fully specialized: T = String, R = Integer, S = Boolean.
 * Tests complete specialization through multi-level inheritance with three type parameters.
 */
public class ConcreteThreeTypes extends GenericMiddleThreeTypes<String, Integer> {

    @Override
    public String processFirst(String value) {
        return "ConcreteThreeTypes.processFirst: " + value;
    }

    @Override
    public String processSecond(Integer value) {
        return "ConcreteThreeTypes.processSecond: " + value;
    }

    @Override
    public Integer transformTtoR(String input) {
        if (input == null) {
            return 0;
        }
        return input.length();
    }

    @Override
    public String transformStoT(Boolean input) {
        return input ? "TRUE" : "FALSE";
    }

    @Override
    public Boolean complexTransform(String first, Integer second) {
        return first != null && first.length() == second;
    }

    @Override
    public String processAndValidate(String input, Integer context) {
        if (input == null || context == null) {
            return "INVALID";
        }
        return "VALID: " + input + " with context " + context;
    }

    @Override
    public String getFirst() {
        return "concrete-first";
    }

    @Override
    public Integer getSecond() {
        return 123;
    }

    @Override
    public Boolean getThird() {
        return true;
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        processFirst("test");
        processSecond(456);
        processThird(true);
        processAll("a", 1, false);
        transformTtoR("hello");
        transformRtoS(999);
        transformStoT(true);
        complexTransform("test", 4);
        combineWithBoolean("x", 42, true);
        processAndValidate("validation", 100);
    }
}

