/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.impl;

import org.junit.Test;

import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

import static org.assertj.core.api.Assertions.assertThat;

public class TimerNameCacheTest {

    @Test
    public void testNullClass() {
        // given
        TimerNameCache timerNameCache = new TimerNameCache();
        // when
        TimerName timerName = timerNameCache.getTimerName((Class<?>) null);
        // then
        assertThat(((TimerNameImpl) timerName).name()).isEqualTo("unknown");
    }

    @Test
    public void testClassWithoutPointcutAnnotation() {
        // given
        TimerNameCache timerNameCache = new TimerNameCache();
        // when
        TimerName timerName = timerNameCache.getTimerName(A.class);
        // then
        assertThat(((TimerNameImpl) timerName).name()).isEqualTo("unknown");
    }

    @Test
    public void testClassWithEmptyTimerName() {
        // given
        TimerNameCache timerNameCache = new TimerNameCache();
        // when
        TimerName timerName = timerNameCache.getTimerName(B.class);
        // then
        assertThat(((TimerNameImpl) timerName).name()).isEqualTo("unknown");
    }

    @Test
    public void testNormal() {
        // given
        TimerNameCache timerNameCache = new TimerNameCache();
        // when
        TimerName timerName = timerNameCache.getTimerName(C.class);
        // then
        assertThat(((TimerNameImpl) timerName).name()).isEqualTo("z");
    }

    private static class A {}

    @Pointcut(className = "x", methodName = "y", methodParameterTypes = {})
    private static class B {}

    @Pointcut(className = "x", methodName = "y", methodParameterTypes = {}, timerName = "z")
    private static class C {}
}
