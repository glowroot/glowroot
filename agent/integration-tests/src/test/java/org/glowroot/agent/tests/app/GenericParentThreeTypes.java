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
 * Generic parent class with THREE type parameters T, R, and S.
 * Tests complex generic specialization scenarios.
 */
public abstract class GenericParentThreeTypes<T, R, S> {

    /**
     * Method with first generic type parameter.
     */
    public String processFirst(T value) {
        return "ThreeTypes.processFirst: " + (value != null ? value.toString() : "null");
    }

    /**
     * Method with second generic type parameter.
     */
    public String processSecond(R value) {
        return "ThreeTypes.processSecond: " + (value != null ? value.toString() : "null");
    }

    /**
     * Method with third generic type parameter.
     */
    public String processThird(S value) {
        return "ThreeTypes.processThird: " + (value != null ? value.toString() : "null");
    }

    /**
     * Method combining all three type parameters.
     */
    public String processAll(T first, R second, S third) {
        return "All: " + first + ", " + second + ", " + third;
    }

    /**
     * Abstract method transforming T to R.
     */
    public abstract R transformTtoR(T input);

    /**
     * Abstract method transforming R to S.
     */
    public abstract S transformRtoS(R input);

    /**
     * Abstract method transforming S to T.
     */
    public abstract T transformStoT(S input);

    /**
     * Method with all three types in signature.
     */
    public abstract S complexTransform(T first, R second);

    public T getFirst() {
        return null;
    }

    public R getSecond() {
        return null;
    }

    public S getThird() {
        return null;
    }
}

