/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.weaving;

import org.junit.Test;
import org.objectweb.asm.Type;

import static org.assertj.core.api.Assertions.assertThat;

public class AnalyzedMethodTest {

    @Test
    public void testTypes() {
        testClass(void.class);
        testClass(boolean.class);
        testClass(char.class);
        testClass(byte.class);
        testClass(short.class);
        testClass(int.class);
        testClass(float.class);
        testClass(long.class);
        testClass(double.class);
        testClass(Object.class);
        testClass(AnalyzedMethodTest.class);

        testClass(boolean[].class);
        testClass(char[].class);
        testClass(byte[].class);
        testClass(short[].class);
        testClass(int[].class);
        testClass(float[].class);
        testClass(long[].class);
        testClass(double[].class);
        testClass(Object[].class);
        testClass(AnalyzedMethodTest[].class);

        testClass(boolean[][].class);
        testClass(char[][].class);
        testClass(byte[][].class);
        testClass(short[][].class);
        testClass(int[][].class);
        testClass(float[][].class);
        testClass(long[][].class);
        testClass(double[][].class);
        testClass(Object[][].class);
        testClass(AnalyzedMethodTest[][].class);

        testClass(boolean[][][].class);
        testClass(char[][][].class);
        testClass(byte[][][].class);
        testClass(short[][][].class);
        testClass(int[][][].class);
        testClass(float[][][].class);
        testClass(long[][][].class);
        testClass(double[][][].class);
        testClass(Object[][][].class);
        testClass(AnalyzedMethodTest[][][].class);
    }

    private void testClass(Class<?> clazz) {
        assertThat(AnalyzedMethod.getType(clazz.getName())).isEqualTo(Type.getType(clazz));
    }
}
