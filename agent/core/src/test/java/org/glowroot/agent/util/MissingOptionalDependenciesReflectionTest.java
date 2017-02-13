/*
 * Copyright 2016-2017 the original author or authors.
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

import com.google.common.base.StandardSystemProperty;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.test.context.cache.DefaultContextCache;

public class MissingOptionalDependenciesReflectionTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void beforeEach() {
        // IBM JVM loads classes with missing optional dependencies just fine
        Assume.assumeFalse(StandardSystemProperty.JAVA_VM_NAME.value().startsWith("IBM"));
    }

    @Test
    public void testGetMethod() throws Exception {
        thrown.expect(NoClassDefFoundError.class);
        DefaultContextCache.class.getMethod("size");
    }

    @Test
    public void testGetDeclaredMethod() throws Exception {
        thrown.expect(NoClassDefFoundError.class);
        DefaultContextCache.class.getDeclaredMethod("size");
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
        thrown.expect(NoSuchMethodException.class);
        Reflections.getAnyMethod(DefaultContextCache.class, "size");
    }

    @Test
    public void testNormal() {
        DefaultContextCache cache = new DefaultContextCache();
        cache.size();
    }
}
