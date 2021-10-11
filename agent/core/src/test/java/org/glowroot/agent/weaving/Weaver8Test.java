/*
 * Copyright 2018 the original author or authors.
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

import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.weaving.SomeAspect.BasicAdvice;
import org.glowroot.agent.weaving.SomeAspect.IterableAdvice;
import org.glowroot.agent.weaving.targets.DefaultMethodAbstractNotIterable.ExtendsDefaultMethodAbstractNotIterable;
import org.glowroot.agent.weaving.targets.DefaultMethodAbstractNotMisc2.ExtendsDefaultMethodAbstractNotMisc2;
import org.glowroot.agent.weaving.targets.DefaultMethodMiscBridge;
import org.glowroot.agent.weaving.targets.DefaultMethodMiscBridge2;
import org.glowroot.agent.weaving.targets.DefaultMethodMiscImpl;
import org.glowroot.agent.weaving.targets.DefaultMethodSubMiscImpl;

import static org.assertj.core.api.Assertions.assertThat;

public class Weaver8Test {

    @Before
    public void before() {
        SomeAspectThreadLocals.resetThreadLocals();
    }

    // ===================== default methods =====================

    @Test
    public void shouldExecuteDefaultMethodAdvice() throws Exception {
        // given
        DefaultMethodMiscBridge test = WeaverTest.newWovenObject(DefaultMethodMiscImpl.class,
                DefaultMethodMiscBridge.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldExecuteDefaultMethodAdvice2() throws Exception {
        // given
        DefaultMethodMiscBridge test = WeaverTest.newWovenObject(DefaultMethodSubMiscImpl.class,
                DefaultMethodMiscBridge.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    // the three tests below document less than ideal behavior around weaving default methods

    @Test
    public void shouldNotWeaveDefaultMethodAdvice() throws Exception {
        // given
        DefaultMethodMiscBridge2 test =
                WeaverTest.newWovenObject(ExtendsDefaultMethodAbstractNotMisc2.class,
                        DefaultMethodMiscBridge2.class, BasicAdvice.class);
        // when
        test.execute2();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldWeaveIterableNonDefaultMethod() throws Exception {
        // given
        Iterable<?> test = WeaverTest.newWovenObject(ExtendsDefaultMethodAbstractNotIterable.class,
                Iterable.class, IterableAdvice.class);
        // when
        test.iterator();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotWeaveIterableDefaultMethod() throws Exception {
        // given
        Iterable<?> test = WeaverTest.newWovenObject(ExtendsDefaultMethodAbstractNotIterable.class,
                Iterable.class, IterableAdvice.class);
        // when
        test.spliterator();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }
}
