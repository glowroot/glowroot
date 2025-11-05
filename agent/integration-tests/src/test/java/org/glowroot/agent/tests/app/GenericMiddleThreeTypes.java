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
 * Intermediate generic class extending GenericParentThreeTypes with partial specialization.
 * Binds S to Boolean while keeping T and R generic.
 */
public abstract class GenericMiddleThreeTypes<T, R> extends GenericParentThreeTypes<T, R, Boolean> {

    @Override
    public String processThird(Boolean value) {
        return "GenericMiddleThreeTypes.processThird: " + value;
    }

    @Override
    public Boolean transformRtoS(R input) {
        return input != null;
    }

    /**
     * New method specific to this middle layer combining T, R, and Boolean.
     */
    public String combineWithBoolean(T first, R second, Boolean flag) {
        return "Combined: " + first + ", " + second + ", flag=" + flag;
    }

    /**
     * Abstract method to be implemented by concrete class.
     */
    public abstract String processAndValidate(T input, R context);
}

