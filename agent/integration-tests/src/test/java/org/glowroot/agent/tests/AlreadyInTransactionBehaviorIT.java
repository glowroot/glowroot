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
package org.glowroot.agent.tests;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.AlreadyInTransactionBehaviorObject;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AlreadyInTransactionBehaviorIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureTraceEntry() throws Exception {
        // when
        Trace trace = container.execute(ShouldCaptureTraceEntry.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getTransactionType()).isEqualTo("Test harness");

        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
    }

    @Test
    public void shouldCaptureNewTransactionPart1() throws Exception {
        // when
        Trace trace = container.execute(ShouldCaptureNewTransaction.class, "Test harness");

        // then
        Trace.Header header = trace.getHeader();
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");

        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).isEmpty();
    }

    @Test
    public void shouldCaptureNewTransactionPart2() throws Exception {
        // when
        Trace trace = container.execute(ShouldCaptureNewTransaction.class, "Test new");

        // then
        Trace.Header header = trace.getHeader();
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(rootTimer.getName()).isEqualTo("already in transaction behavior one");

        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).isEmpty();
    }

    public static class ShouldCaptureTraceEntry implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            new AlreadyInTransactionBehaviorObject().call("CAPTURE_TRACE_ENTRY");
        }
    }

    public static class ShouldCaptureNewTransaction implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            new AlreadyInTransactionBehaviorObject().call("CAPTURE_NEW_TRANSACTION");
        }
    }
}
