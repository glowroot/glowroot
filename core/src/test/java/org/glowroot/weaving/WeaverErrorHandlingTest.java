/*
 * Copyright 2012-2014 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.weaving.SomeAspect.BindPrimitiveBooleanTravelerBadAdvice;
import org.glowroot.weaving.SomeAspect.BindPrimitiveTravelerBadAdvice;
import org.glowroot.weaving.SomeAspect.MoreVeryBadAdvice;
import org.glowroot.weaving.SomeAspect.MoreVeryBadAdvice2;
import org.glowroot.weaving.SomeAspect.VeryBadAdvice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeaverErrorHandlingTest {

    @Test
    public void shouldHandleVoidPrimitiveTravelerGracefully() throws Exception {
        // given
        BindPrimitiveTravelerBadAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                BindPrimitiveTravelerBadAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(BindPrimitiveTravelerBadAdvice.onReturnTraveler.get()).isEqualTo(0);
        assertThat(BindPrimitiveTravelerBadAdvice.onThrowTraveler.get()).isNull();
        assertThat(BindPrimitiveTravelerBadAdvice.onAfterTraveler.get()).isEqualTo(0);
    }

    @Test
    public void shouldHandleVoidPrimitiveBooleanTravelerGracefully() throws Exception {
        // given
        BindPrimitiveBooleanTravelerBadAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                BindPrimitiveBooleanTravelerBadAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(BindPrimitiveBooleanTravelerBadAdvice.onReturnTraveler.get()).isEqualTo(false);
        assertThat(BindPrimitiveBooleanTravelerBadAdvice.onThrowTraveler.get()).isNull();
        assertThat(BindPrimitiveBooleanTravelerBadAdvice.onAfterTraveler.get()).isEqualTo(false);
    }

    @Test
    public void shouldNotCallOnThrowForOnBeforeException() throws Exception {
        // given
        VeryBadAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, VeryBadAdvice.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspect.onBeforeCount.get()).isEqualTo(1);
            assertThat(SomeAspect.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspect.onAfterCount.get()).isEqualTo(0);
            return;
        }
        throw new AssertionError("Expecting IllegalStateException");
    }

    @Test
    public void shouldNotCallOnThrowForOnReturnException() throws Exception {
        // given
        MoreVeryBadAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MoreVeryBadAdvice.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspect.onReturnCount.get()).isEqualTo(1);
            assertThat(SomeAspect.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspect.onAfterCount.get()).isEqualTo(0);
            return;
        }
        throw new AssertionError("Expecting IllegalStateException");
    }

    // same as MoreVeryBadAdvice, but testing weaving a method with a non-void return type
    @Test
    public void shouldNotCallOnThrowForOnReturnException2() throws Exception {
        // given
        MoreVeryBadAdvice2.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MoreVeryBadAdvice2.class);
        // when
        try {
            test.executeWithReturn();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("Sorry");
            assertThat(SomeAspect.onReturnCount.get()).isEqualTo(1);
            assertThat(SomeAspect.onThrowCount.get()).isEqualTo(0);
            assertThat(SomeAspect.onAfterCount.get()).isEqualTo(0);
            return;
        }
        throw new AssertionError("Expecting IllegalStateException");
    }

    public static <S, T extends S> S newWovenObject(Class<T> implClass, Class<S> bridgeClass,
            Class<?> adviceClass, Class<?>... extraBridgeClasses) throws Exception {

        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut != null) {
            loader.setAdvisors(ImmutableList.of(Advice.from(pointcut, adviceClass, false)));
        }
        Mixin mixin = adviceClass.getAnnotation(Mixin.class);
        if (mixin != null) {
            loader.setMixinTypes(ImmutableList.of(MixinType.from(mixin, adviceClass)));
        }
        loader.setMetricTimerService(NopMetricTimerService.INSTANCE);
        // adviceClass is passed as bridgeable so that the static threadlocals will be accessible
        // for test verification
        loader.addBridgeClasses(bridgeClass, adviceClass);
        loader.addBridgeClasses(extraBridgeClasses);
        return loader.build().newInstance(implClass, bridgeClass);
    }

    private static class NopMetricTimerService implements MetricTimerService {
        private static final NopMetricTimerService INSTANCE = new NopMetricTimerService();
        @Override
        public MetricName getMetricName(String name) {
            return NopMetricName.INSTANCE;
        }
        @Override
        public MetricTimer startMetricTimer(MetricName metricName) {
            return NopMetricTimer.INSTANCE;
        }
    }

    private static class NopMetricName implements MetricName {
        private static final NopMetricName INSTANCE = new NopMetricName();
    }

    private static class NopMetricTimer implements MetricTimer {
        private static final NopMetricTimer INSTANCE = new NopMetricTimer();
        @Override
        public void stop() {}
    }
}
