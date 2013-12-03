/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.weaving;

import org.junit.Before;
import org.junit.Test;

import org.glowroot.weaving.Weaver.ComputeFramesClassWriter;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ComputeFramesClassWriterTest {

    private ComputeFramesClassWriter cw;

    @Before
    public void beforeEachTest() {
        cw = new ComputeFramesClassWriter(0, new ParsedTypeCache(), getClass().getClassLoader(),
                null, ComputeFramesClassWriterTest.class.getName());
    }

    @Test
    public void shouldFindCommonSuperClass() {
        assertCommonSuperType(C.class, Y.class, B.class);
        assertCommonSuperType(C.class, B.class, B.class);
        assertCommonSuperType(C.class, A.class, A.class);
        assertCommonSuperType(C.class, I.class, I.class);
    }

    private <R, S extends R, T extends R> void assertCommonSuperType(Class<S> type1,
            Class<T> type2, Class<R> commonSuperType) {

        String commonSuperTypeName = cw.getCommonSuperClass(
                internalTypeName(type1), internalTypeName(type2));
        assertThat(commonSuperTypeName).isEqualTo(internalTypeName(commonSuperType));
    }

    private static String internalTypeName(Class<?> type) {
        return TypeNames.toInternal(type.getName());
    }

    static interface I {}

    static class A {}

    static class B extends A {}

    static class C extends B implements I {}

    static class X extends B {}

    static class Y extends X {}
}
