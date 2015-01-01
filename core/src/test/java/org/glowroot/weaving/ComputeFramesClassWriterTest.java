/*
 * Copyright 2013-2015 the original author or authors.
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

import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.weaving.Weaver.ComputeFramesClassWriter;

import static org.assertj.core.api.Assertions.assertThat;

public class ComputeFramesClassWriterTest {

    private ComputeFramesClassWriter cw;

    @Before
    public void beforeEachTest() {
        Supplier<List<Advice>> advisors =
                Suppliers.<List<Advice>>ofInstance(ImmutableList.<Advice>of());
        AnalyzedWorld analyzedWorld =
                new AnalyzedWorld(advisors, ImmutableList.<MixinType>of(), null);
        cw = new ComputeFramesClassWriter(0, analyzedWorld, getClass().getClassLoader(), null,
                ComputeFramesClassWriterTest.class.getName());
    }

    @Test
    public void shouldFindCommonSuperClass() {
        assertCommonSuperClass(C.class, Y.class, B.class);
        assertCommonSuperClass(C.class, B.class, B.class);
        assertCommonSuperClass(C.class, A.class, A.class);
        assertCommonSuperClass(C.class, I.class, I.class);
    }

    private <R, S extends R, T extends R> void assertCommonSuperClass(Class<S> class1,
            Class<T> class2, Class<R> commonSuperClass) {

        String commonSuperInternalName =
                cw.getCommonSuperClass(internalName(class1), internalName(class2));
        assertThat(commonSuperInternalName).isEqualTo(internalName(commonSuperClass));
    }

    private static String internalName(Class<?> clazz) {
        return ClassNames.toInternalName(clazz.getName());
    }

    static interface I {}

    static class A {}

    static class B extends A {}

    static class C extends B implements I {}

    static class X extends B {}

    static class Y extends X {}
}
