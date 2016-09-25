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

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TraceEntryComponentTest {

    @Test
    public void testTooManyPops() {
        // given
        ThreadContextImpl threadContext = mock(ThreadContextImpl.class);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        when(messageSupplier.get()).thenReturn(Message.create("abc"));
        TimerImpl timer = mock(TimerImpl.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(threadContext, messageSupplier, timer, 0);
        // when
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0);
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0);
        // then ok
    }

    @Test
    public void testMissedPop() {
        // given
        ThreadContextImpl threadContext = mock(ThreadContextImpl.class);
        MessageSupplier messageSupplier1 = mock(MessageSupplier.class);
        MessageSupplier messageSupplier2 = mock(MessageSupplier.class);
        when(messageSupplier1.get()).thenReturn(Message.create("abc"));
        when(messageSupplier2.get()).thenReturn(Message.create("xyz"));
        TimerImpl timer1 = mock(TimerImpl.class);
        TimerImpl timer2 = mock(TimerImpl.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(threadContext, messageSupplier1, timer1, 0);
        // when
        traceEntryComponent.pushEntry(0, messageSupplier2, timer2, null, null, 0);
        traceEntryComponent.popEntry(traceEntryComponent.getRootEntry(), 0);
        // then
        assertThat(traceEntryComponent.isCompleted()).isTrue();
    }

    @Test
    public void testPopNonExisting() {
        // given
        ThreadContextImpl threadContext = mock(ThreadContextImpl.class);
        MessageSupplier messageSupplier1 = mock(MessageSupplier.class);
        MessageSupplier messageSupplier2 = mock(MessageSupplier.class);
        when(messageSupplier1.get()).thenReturn(Message.create("abc"));
        when(messageSupplier2.get()).thenReturn(Message.create("xyz"));
        TimerImpl timer1 = mock(TimerImpl.class);
        TimerImpl timer2 = mock(TimerImpl.class);
        TraceEntryComponent traceEntryComponent =
                new TraceEntryComponent(threadContext, messageSupplier1, timer1, 0);
        // when
        traceEntryComponent.pushEntry(0, messageSupplier2, timer2, null, null, 0);
        traceEntryComponent.popEntry(mock(TraceEntryImpl.class), 0);
        // then
        assertThat(traceEntryComponent.isCompleted()).isFalse();
    }
}
