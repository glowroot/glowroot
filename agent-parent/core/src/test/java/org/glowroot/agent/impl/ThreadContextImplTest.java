/*
 * Copyright 2016 the original author or authors.
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

import com.google.common.base.Ticker;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncTraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ThreadContextImplTest {

    private ThreadContextImpl threadContext;

    private MessageSupplier messageSupplier = mock(MessageSupplier.class);
    private TimerNameImpl timerName = mock(TimerNameImpl.class);

    @Before
    public void beforeEachTest() {
        Transaction transaction = mock(Transaction.class);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerNameImpl rootTimerName = mock(TimerNameImpl.class);
        Ticker ticker = mock(Ticker.class);
        @SuppressWarnings("unchecked")
        Holder<ThreadContextImpl> threadContextHolder = mock(Holder.class);
        threadContext = new ThreadContextImpl(transaction, null, null, messageSupplier,
                rootTimerName, 0, false, null, false, ticker, threadContextHolder, null);
    }

    @Test
    public void testStartTransaction() {
        assertThat(threadContext.startTransaction(null, "text", messageSupplier, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startTransaction("type", null, messageSupplier, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startTransaction("type", "text", null, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startTransaction("type", "text", messageSupplier, null))
                .isEqualTo(NopTraceEntry.INSTANCE);
    }

    @Test
    public void testStartTraceEntry() {
        assertThat(threadContext.startTraceEntry(null, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startTraceEntry(messageSupplier, null))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startTraceEntry(messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartAsyncTraceEntry() {
        assertThat(threadContext.startAsyncTraceEntry(null, timerName))
                .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(threadContext.startAsyncTraceEntry(messageSupplier, null))
                .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(threadContext.startAsyncTraceEntry(messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartQueryEntry() {
        assertThat(threadContext.startQueryEntry(null, "text", messageSupplier, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", null, messageSupplier, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", null, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", messageSupplier, null))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartQueryEntryWithExecutionCount() {
        assertThat(threadContext.startQueryEntry(null, "text", 0, messageSupplier, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", null, 0, messageSupplier, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", 0, null, timerName))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", 0, messageSupplier, null))
                .isEqualTo(NopQueryEntry.INSTANCE);
        assertThat(threadContext.startQueryEntry("type", "text", 0, messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartAsyncQueryEntry() {
        assertThat(threadContext.startAsyncQueryEntry(null, "text", messageSupplier, timerName))
                .isEqualTo(NopAsyncQueryEntry.INSTANCE);
        assertThat(threadContext.startAsyncQueryEntry("type", null, messageSupplier, timerName))
                .isEqualTo(NopAsyncQueryEntry.INSTANCE);
        assertThat(threadContext.startAsyncQueryEntry("type", "text", null, timerName))
                .isEqualTo(NopAsyncQueryEntry.INSTANCE);
        assertThat(threadContext.startAsyncQueryEntry("type", "text", messageSupplier, null))
                .isEqualTo(NopAsyncQueryEntry.INSTANCE);
        assertThat(threadContext.startAsyncQueryEntry("type", "text", messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartServiceCallEntry() {
        assertThat(threadContext.startServiceCallEntry(null, "text", messageSupplier, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startServiceCallEntry("type", null, messageSupplier, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startServiceCallEntry("type", "text", null, timerName))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startServiceCallEntry("type", "text", messageSupplier, null))
                .isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(threadContext.startServiceCallEntry("type", "text", messageSupplier, timerName)
                .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartAsyncServiceCallEntry() {
        assertThat(
                threadContext.startAsyncServiceCallEntry(null, "text", messageSupplier, timerName))
                        .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(
                threadContext.startAsyncServiceCallEntry("type", null, messageSupplier, timerName))
                        .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(threadContext.startAsyncServiceCallEntry("type", "text", null, timerName))
                .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(threadContext.startAsyncServiceCallEntry("type", "text", messageSupplier, null))
                .isEqualTo(NopAsyncTraceEntry.INSTANCE);
        assertThat(
                threadContext.startAsyncServiceCallEntry("type", "text", messageSupplier, timerName)
                        .getClass().getName()).endsWith("$DummyTraceEntryOrQuery");
    }

    @Test
    public void testStartTimer() {
        assertThat(threadContext.startTimer(null)).isEqualTo(NopTimer.INSTANCE);

        threadContext.setCurrentTimer(null);
        assertThat(threadContext.startTimer(timerName)).isEqualTo(NopTimer.INSTANCE);
    }

    @Test
    public void testSetters() {
        threadContext.setTransactionType(null, 0);
        threadContext.setTransactionName(null, 0);
        threadContext.setTransactionUser(null, 0);
        threadContext.addTransactionAttribute(null, null);
        threadContext.setTransactionSlowThreshold(-1, MILLISECONDS, 0);
        threadContext.setTransactionSlowThreshold(0, null, 0);
        threadContext.setTransactionError((String) null);
    }
}
