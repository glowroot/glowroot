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
package org.glowroot.agent.tests;

import java.util.Iterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ExceptionWithLotsOfCausesIT {

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
    public void testCausedBy() throws Exception {
        // when
        Trace trace = container.execute(ShouldThrowExceptionWithLotsOfCauses.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.hasError()).isTrue();
        Trace.Error error = entry.getError();
        assertThat(error.hasException()).isTrue();
        Proto.Throwable exception = error.getException();
        for (int j = 0; j < 80; j++) {
            assertThat(exception.hasCause()).isTrue();
            exception = exception.getCause();
        }
        assertThat(exception.hasCause()).isTrue();
        Proto.Throwable finalException = exception.getCause();
        assertThat(finalException.hasCause()).isFalse();
        assertThat(finalException.getMessage())
                .isEqualTo("The rest of the causal chain for this exception has been truncated");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldThrowExceptionWithLotsOfCauses
            implements AppUnderTest, TransactionMarker, TraceEntryMarker {

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            try {
                traceEntryMarker();
            } catch (RuntimeException e) {
            }
        }

        @Override
        public void traceEntryMarker() {
            recurse(0);
        }

        private void recurse(int depth) {
            if (depth == 200) {
                throw new RuntimeException("The real cause");
            }
            try {
                recurse(depth + 1);
            } catch (RuntimeException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
