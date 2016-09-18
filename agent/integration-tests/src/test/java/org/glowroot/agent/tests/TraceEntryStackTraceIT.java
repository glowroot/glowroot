/*
 * Copyright 2011-2016 the original author or authors.
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
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.Pause;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceEntryStackTraceIT {

    private static final String PLUGIN_ID = "glowroot-integration-tests";

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
    public void shouldReadTraceEntryStackTrace() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureTraceEntryStackTraces",
                true);

        // when
        Trace trace = container.execute(ShouldGenerateTraceWithTraceEntryStackTrace.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        List<Proto.StackTraceElement> stackTraceElements = entry.getLocationStackTraceElementList();
        assertThat(stackTraceElements).isNotEmpty();
        assertThat(stackTraceElements.get(0).getClassName()).isEqualTo(Pause.class.getName());
        assertThat(stackTraceElements.get(0).getMethodName()).isEqualTo("pauseOneMillisecond");
        assertThat(stackTraceElements.get(0).getFileName())
                .isEqualTo(Pause.class.getSimpleName() + ".java");
        for (Proto.StackTraceElement stackTraceElement : stackTraceElements) {
            assertThat(stackTraceElement.getMethodName()).doesNotContain("$glowroot$");
        }

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldGenerateTraceWithTraceEntryStackTrace
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            new Pause().pauseOneMillisecond();
        }
    }
}
