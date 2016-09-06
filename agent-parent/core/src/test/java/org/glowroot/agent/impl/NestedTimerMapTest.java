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

import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.Maps;
import org.junit.Test;

import org.glowroot.agent.model.ImmutableTimerNameImpl;
import org.glowroot.agent.model.TimerNameImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class NestedTimerMapTest {

    @Test
    public void testBucketCollision() {
        // given
        NestedTimerMap map = new NestedTimerMap();
        Map<TimerNameImpl, TimerImpl> uniqueTimers = Maps.newHashMap();
        for (int i = 0; i < 100; i++) {
            uniqueTimers.put(ImmutableTimerNameImpl.of("timer-" + i, false), mock(TimerImpl.class));
        }
        // when
        for (Entry<TimerNameImpl, TimerImpl> entry : uniqueTimers.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        // then
        for (Entry<TimerNameImpl, TimerImpl> entry : uniqueTimers.entrySet()) {
            assertThat(map.get(entry.getKey())).isEqualTo(entry.getValue());
        }
    }
}
