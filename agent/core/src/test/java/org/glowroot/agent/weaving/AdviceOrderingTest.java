/*
 * Copyright 2015-2017 the original author or authors.
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

import com.google.common.collect.Ordering;
import org.junit.Test;
import org.objectweb.asm.Type;

import org.glowroot.agent.plugin.api.weaving.Pointcut;

import static org.assertj.core.api.Assertions.assertThat;

public class AdviceOrderingTest {

    private final Pointcut pointcutPriority1 =
            OnlyForTheOrder1.class.getAnnotation(Pointcut.class);

    private final Pointcut pointcutPriority2 =
            OnlyForTheOrder2.class.getAnnotation(Pointcut.class);

    private final Pointcut pointcutTimerNameA =
            OnlyForTheTimerNameA.class.getAnnotation(Pointcut.class);

    private final Pointcut pointcutTimerNameB =
            OnlyForTheTimerNameB.class.getAnnotation(Pointcut.class);

    private final Pointcut pointcutTimerNameEmpty1 =
            OnlyForTheTimerNameEmpty1.class.getAnnotation(Pointcut.class);

    private final Pointcut pointcutTimerNameEmpty2 =
            OnlyForTheTimerNameEmpty2.class.getAnnotation(Pointcut.class);

    private final Advice advicePriority1 = ImmutableAdvice.builder()
            .pointcut(pointcutPriority1)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    private final Advice advicePriority2 = ImmutableAdvice.builder()
            .pointcut(pointcutPriority2)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    private final Advice adviceTimerNameA = ImmutableAdvice.builder()
            .pointcut(pointcutTimerNameA)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    private final Advice adviceTimerNameB = ImmutableAdvice.builder()
            .pointcut(pointcutTimerNameB)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    private final Advice adviceTimerNameEmpty1 = ImmutableAdvice.builder()
            .pointcut(pointcutTimerNameEmpty1)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    private final Advice adviceTimerNameEmpty2 = ImmutableAdvice.builder()
            .pointcut(pointcutTimerNameEmpty2)
            .adviceType(Type.getType(AdviceOrderingTest.class))
            .reweavable(false)
            .hasBindThreadContext(false)
            .hasBindOptionalThreadContext(false)
            .build();

    @Test
    public void shouldCompare() {
        Ordering<Advice> ordering = Advice.ordering;
        assertThat(ordering.compare(advicePriority1, advicePriority2)).isNegative();
        assertThat(ordering.compare(advicePriority2, advicePriority1)).isPositive();
        assertThat(ordering.compare(adviceTimerNameA, adviceTimerNameB)).isNegative();
        assertThat(ordering.compare(adviceTimerNameB, adviceTimerNameA)).isPositive();
        assertThat(ordering.compare(adviceTimerNameA, adviceTimerNameEmpty1)).isNegative();
        assertThat(ordering.compare(adviceTimerNameEmpty1, adviceTimerNameA)).isPositive();
        assertThat(ordering.compare(adviceTimerNameEmpty1, adviceTimerNameEmpty2)).isZero();
        assertThat(ordering.compare(adviceTimerNameEmpty2, adviceTimerNameEmpty1)).isZero();
    }

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {}, timerName = "b",
            order = 1)
    private static class OnlyForTheOrder1 {}

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {}, timerName = "a",
            order = 2)
    private static class OnlyForTheOrder2 {}

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {}, timerName = "a")
    private static class OnlyForTheTimerNameA {}

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {}, timerName = "b")
    private static class OnlyForTheTimerNameB {}

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {})
    private static class OnlyForTheTimerNameEmpty1 {}

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {})
    private static class OnlyForTheTimerNameEmpty2 {}
}
