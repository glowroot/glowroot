/*
 * Copyright 2012-2016 the original author or authors.
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

import org.glowroot.agent.weaving.SomeAspect.BindPrimitiveBooleanTravelerBadAdvice;
import org.glowroot.agent.weaving.SomeAspect.BindPrimitiveTravelerBadAdvice;
import org.glowroot.agent.weaving.SomeAspect.MoreVeryBadAdvice;
import org.glowroot.agent.weaving.SomeAspect.MoreVeryBadAdvice2;
import org.glowroot.agent.weaving.SomeAspect.VeryBadAdvice;
import org.glowroot.agent.weaving.targets.BasicMisc;
import org.glowroot.agent.weaving.targets.Misc;

import static org.assertj.core.api.Assertions.assertThat;

public class WeaverErrorHandlingTest {

    @Test
    public void shouldHandleVoidPrimitiveTravelerGracefully() throws Exception {
        // given
        SomeAspectThreadLocals.resetThreadLocals();
        Misc test =
                newWovenObject(BasicMisc.class, Misc.class, BindPrimitiveTravelerBadAdvice.class);

        // when
        test.execute1();

        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo(0);
    }

    @Test
    public void shouldHandleVoidPrimitiveBooleanTravelerGracefully() throws Exception {
        // given
        SomeAspectThreadLocals.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                BindPrimitiveBooleanTravelerBadAdvice.class);

        // when
        test.execute1();

        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isEqualTo(false);
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo(false);
    }

    @Test
    public void shouldNotCallOnThrowForOnBeforeException() throws Exception {
        // given
        SomeAspectThreadLocals.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, VeryBadAdvice.class);

        // when
        IllegalStateException exception = null;
        try {
            test.executeWithArgs("one", 2);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
            assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
            exception = e;
        }

        // then
        assertThat(exception).isNotNull();
    }

    @Test
    public void shouldNotCallOnThrowForOnReturnException() throws Exception {
        // given
        SomeAspectThreadLocals.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MoreVeryBadAdvice.class);

        // when
        IllegalStateException exception = null;
        try {
            test.executeWithArgs("one", 2);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
            assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
            exception = e;
        }

        // then
        assertThat(exception).isNotNull();
    }

    // same as MoreVeryBadAdvice, but testing weaving a method with a non-void return type
    @Test
    public void shouldNotCallOnThrowForOnReturnException2() throws Exception {
        // given
        SomeAspectThreadLocals.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MoreVeryBadAdvice2.class);

        // when
        IllegalStateException exception = null;
        try {
            test.executeWithReturn();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
            assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
            exception = e;
        }

        // then
        assertThat(exception).isNotNull();
    }

    private static <S, T extends S> S newWovenObject(Class<T> implClass, Class<S> bridgeClass,
            Class<?> adviceClass) throws Exception {
        // adviceClass is passed as bridgeable so that the static threadlocals will be accessible
        // for test verification
        return WeaverTest.newWovenObject(implClass, bridgeClass, adviceClass);
    }
}
