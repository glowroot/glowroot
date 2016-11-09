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
package org.glowroot.agent.tests;

import java.util.Iterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class GlowrootApiInstrumentationIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
        // when
        Trace trace = container.execute(CaptureTransaction.class);
        // then
        assertThat(trace.getHeader().getTransactionType()).isEqualTo("abc type");
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("xyz zyx");
    }

    @Test
    public void shouldCaptureTraceEntry() throws Exception {
        // when
        Trace trace = container.execute(CaptureTraceEntry.class);

        // then
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimer(0).getName()).isEqualTo("ooo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("xyz zyx => zyx0");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTimer() throws Exception {
        // when
        Trace trace = container.execute(CaptureTimer.class);
        // then
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimer(0).getName()).isEqualTo("qqq");
    }

    public static class CaptureTransaction implements AppUnderTest {

        @Override
        public void executeApp() {
            captureTransaction("zyx");
        }

        @Instrumentation.Transaction(transactionType = "abc type", transactionName = "xyz {{0}}",
                traceHeadline = "abc xyz {{0}}", timer = "mmm")
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

        @Instrumentation.TraceEntry(message = "xyz {{0}} => {{_}}", timer = "ooo")
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
        @Instrumentation.Timer("qqq")
        void qqq();
    }

    public static class PPP implements QQQ {
        @Override
        public void qqq() {}
    }
}
