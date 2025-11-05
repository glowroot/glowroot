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
 * Concrete class extending GenericParentBounded<Double>.
 * Tests specialization with bounded type parameters (Double extends Number).
 */
public class ConcreteBounded extends GenericParentBounded<Double> {

    @Override
    public String processNumber(Double value) {
        return "ConcreteBounded.processNumber: " + super.processNumber(value);
    }

    @Override
    public Double multiplyByTwo(Double input) {
        if (input == null) {
            return 0.0;
        }
        return input * 2;
    }

    @Override
    public Double getValue() {
        return 3.14;
    }

    /**
     * Additional method specific to Double.
     */
    public String formatDouble(Double value) {
        if (value == null) {
            return "null";
        }
        return String.format("%.2f", value);
    }

    /**
     * Execute method to test all inherited and overridden methods.
     */
    public void execute() {
        processNumber(5.5);
        calculateDouble(10.0);
        multiplyByTwo(7.3);
        compare(3.0, 5.0);
        formatDouble(2.71828);
    }
}

