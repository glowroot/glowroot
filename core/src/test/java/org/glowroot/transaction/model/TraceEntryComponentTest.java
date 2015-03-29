/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.transaction.model;

import com.google.common.base.Ticker;
import org.junit.Test;

import org.glowroot.api.MessageSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class TraceEntryComponentTest {

    @Test
    public void testTooManyPops() {
        // given
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerImpl timer = mock(TimerImpl.class);
        Ticker ticker = mock(Ticker.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(messageSupplier, timer, 0, ticker);
        // when
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0, null);
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0, null);
        // then ok
    }

    @Test
    public void testMissedPop() {
        // given
        MessageSupplier messageSupplier1 = mock(MessageSupplier.class);
        MessageSupplier messageSupplier2 = mock(MessageSupplier.class);
        TimerImpl timer1 = mock(TimerImpl.class);
        TimerImpl timer2 = mock(TimerImpl.class);
        Ticker ticker = mock(Ticker.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(messageSupplier1, timer1, 0, ticker);
        // when
        traceEntryComponent.pushEntry(0, messageSupplier2, timer2);
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0, null);
        // then
        assertThat(traceEntryComponent.isCompleted()).isTrue();
    }

    @Test
    public void testPopNonExisting() {
        // given
        MessageSupplier messageSupplier1 = mock(MessageSupplier.class);
        MessageSupplier messageSupplier2 = mock(MessageSupplier.class);
        TimerImpl timer1 = mock(TimerImpl.class);
        TimerImpl timer2 = mock(TimerImpl.class);
        Ticker ticker = mock(Ticker.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(messageSupplier1, timer1, 0, ticker);
        // when
        traceEntryComponent.pushEntry(0, messageSupplier2, timer2);
        traceEntryComponent.popEntry(mock(TraceEntry.class), 0, null);
        // then
        assertThat(traceEntryComponent.isCompleted()).isTrue();
    }
}
