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
package org.glowroot.agent.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.api.Instrument;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class GlowrootApiInstrumentIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureTransaction() throws Exception {
        // given
        // when
        Trace trace = container.execute(CaptureTransaction.class);
        // then
        assertThat(trace.getHeader().getTransactionType()).isEqualTo("abc type");
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("xyz zyx");
    }

    @Test
    public void shouldCaptureTraceEntry() throws Exception {
        // given
        // when
        Trace trace = container.execute(CaptureTraceEntry.class);
        // then
        assertThat(trace.getEntryList().get(0).getMessage()).isEqualTo("xyz zyx => zyx0");
        assertThat(trace.getHeader().getRootTimer().getChildTimer(0).getName()).isEqualTo("ooo");
    }

    @Test
    public void shouldCaptureTimer() throws Exception {
        // given
        // when
        Trace trace = container.execute(CaptureTimer.class);
        // then
        assertThat(trace.getHeader().getRootTimer().getChildTimer(0).getName()).isEqualTo("qqq");
    }

    public static class CaptureTransaction implements AppUnderTest {

        @Override
        public void executeApp() {
            captureTransaction("zyx");
        }

        @Instrument.Transaction(transactionType = "abc type",
                transactionNameTemplate = "xyz {{0}}", timerName = "mmm")
        public void captureTransaction(@SuppressWarnings("unused") String str) {}
    }

    public static class CaptureTraceEntry implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            captureTraceEntry("zyx");
        }

        @Instrument.TraceEntry(messageTemplate = "xyz {{0}} => {{_}}", timerName = "ooo")
        public String captureTraceEntry(String str) {
            return str + "0";
        }
    }

    public static class CaptureTimer implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            new PPP().qqq();
        }
    }

    public interface QQQ {
        @Instrument.Timer("qqq")
        void qqq();
    }

    public static class PPP implements QQQ {
        @Override
        public void qqq() {}
    }
}
