/*
 * Copyright 2016-2023 the original author or authors.
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
package org.glowroot.agent.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.cache.DefaultContextCache;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class MissingOptionalDependenciesReflectionTest {

    @BeforeAll
    public static void setUp() {
        // IBM J9 VM and Eclipse OpenJ9 JM load classes with missing optional dependencies just fine
        Assumptions.assumeFalse(JavaVersion.isJ9Jvm());
    }

    @Test
    public void testGetMethod() {
        assertThrows(NoClassDefFoundError.class, () ->
                DefaultContextCache.class.getMethod("size"));
    }

    @Test
    public void testGetDeclaredMethod() {
        assertThrows(NoClassDefFoundError.class, () ->
                DefaultContextCache.class.getDeclaredMethod("size"));
    }

    @Test
    public void testGetConstructor() throws Exception {
        DefaultContextCache.class.getConstructor();
    }

    @Test
    public void testGetField() throws Exception {
        DefaultContextCache.class.getDeclaredField("hitCount");
    }

    @Test
    public void testReflectionsGetAnyMethod() throws Exception {
        assertThrows(NoSuchMethodException.class, () ->
                Reflections.getAnyMethod(DefaultContextCache.class, "size"));
    }

    @Test
    public void testNormal() {
        DefaultContextCache cache = new DefaultContextCache();
        cache.size();
    }
}
