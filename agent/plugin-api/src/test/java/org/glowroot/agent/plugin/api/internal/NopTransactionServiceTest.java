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
package org.glowroot.agent.plugin.api.internal;

import org.junit.Test;

import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncTraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAuxThreadContext;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimerName;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class NopTransactionServiceTest {

    @Test
    public void testNopTraceEntry() {
        NopTraceEntry.INSTANCE.end();
        NopTraceEntry.INSTANCE.endWithStackTrace(0, MILLISECONDS);
        NopTraceEntry.INSTANCE.endWithError(new Throwable());
        NopTraceEntry.INSTANCE.endWithError("");
        NopTraceEntry.INSTANCE.endWithError("", new Throwable());
        NopTraceEntry.INSTANCE.endWithInfo(new Throwable());
        assertThat(NopTraceEntry.INSTANCE.getMessageSupplier()).isNull();
    }

    @Test
    public void testNopQueryEntry() {
        assertThat(NopQueryEntry.INSTANCE.extend()).isEqualTo(NopTimer.INSTANCE);
        NopQueryEntry.INSTANCE.rowNavigationAttempted();
        NopQueryEntry.INSTANCE.incrementCurrRow();
        NopQueryEntry.INSTANCE.setCurrRow(0);
    }

    @Test
    public void testNopAsyncTraceEntry() {
        NopAsyncTraceEntry.INSTANCE.stopSyncTimer();
        assertThat(NopAsyncTraceEntry.INSTANCE.extendSyncTimer(null)).isEqualTo(NopTimer.INSTANCE);
    }

    @Test
    public void testNopAsyncQueryEntry() {
        NopAsyncQueryEntry.INSTANCE.stopSyncTimer();
        assertThat(NopAsyncQueryEntry.INSTANCE.extendSyncTimer(null)).isEqualTo(NopTimer.INSTANCE);
    }

    @Test
    public void testNopAuxThreadContext() {
        assertThat(NopAuxThreadContext.INSTANCE.start()).isEqualTo(NopTraceEntry.INSTANCE);
        assertThat(NopAuxThreadContext.INSTANCE.startAndMarkAsyncTransactionComplete())
                .isEqualTo(NopTraceEntry.INSTANCE);
    }

    @Test
    public void testNopTimer() {
        NopTimer.INSTANCE.stop();
        assertThat(NopTimer.INSTANCE.extend()).isEqualTo(NopTimer.INSTANCE);
    }

    @Test
    public void testNopTimerName() {
        NopTimerName.INSTANCE.toString();
    }
}
