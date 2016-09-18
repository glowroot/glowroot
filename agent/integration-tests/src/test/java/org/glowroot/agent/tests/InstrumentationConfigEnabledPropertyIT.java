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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

// InstrumentationConfig's enabledProperty and traceEntryEnabledProperty can only be used by plugins
// and must be configured in glowroot.plugin.json
public class InstrumentationConfigEnabledPropertyIT {

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
    public void shouldReadTracesWithEnabledFifthTimer() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);

        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNestedEntries.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");
        assertThat(header.getTransactionName()).isEqualTo("basic test");
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getName()).isEqualTo("level one");
        assertThat(rootTimer.getChildTimerList().get(0).getName()).isEqualTo("level two");
        Trace.Timer levelTwoTimer = rootTimer.getChildTimerList().get(0);
        assertThat(levelTwoTimer.getChildTimerList().get(0).getName()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.getChildTimerList().get(0);
        assertThat(levelThreeTimer.getChildTimerList().get(0).getName()).isEqualTo("level four");
        Trace.Timer levelFourTimer = levelThreeTimer.getChildTimerList().get(0);
        assertThat(levelFourTimer.getChildTimerList().get(0).getName()).isEqualTo("level five");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("Level Two");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("Level Three");

        // there's no way offsetNanos should be 0
        assertThat(entry.getStartOffsetNanos()).isGreaterThan(0);

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).isEqualTo("Level Four: axy, bxy");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldReadTracesWithEnabledFifthEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEntryEnabled", true);

        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNestedEntries.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");
        assertThat(header.getTransactionName()).isEqualTo("basic test");
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getName()).isEqualTo("level one");
        assertThat(rootTimer.getChildTimerList().get(0).getName()).isEqualTo("level two");
        Trace.Timer levelTwoTimer = rootTimer.getChildTimerList().get(0);
        assertThat(levelTwoTimer.getChildTimerList().get(0).getName()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.getChildTimerList().get(0);
        assertThat(levelThreeTimer.getChildTimerList().get(0).getName()).isEqualTo("level four");
        Trace.Timer levelFourTimer = levelThreeTimer.getChildTimerList().get(0);
        assertThat(levelFourTimer.getChildTimerList().get(0).getName()).isEqualTo("level five");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("Level Two");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("Level Three");

        // there's no way offsetNanos should be 0
        assertThat(entry.getStartOffsetNanos()).isGreaterThan(0);

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(2);
        assertThat(entry.getMessage()).isEqualTo("Level Four: axy, bxy");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(3);
        assertThat(entry.getMessage()).isEqualTo("Level Five: axy, bxy");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }
}
