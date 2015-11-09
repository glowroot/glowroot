/*
 * Copyright 2011-2015 the original author or authors.
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

import java.io.File;
import java.util.List;

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

public class DetailMapIT {

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
    public void shouldReadDetailMap() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        assertThat(details).hasSize(4);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getSval()).isEqualTo("b");
        assertThat(details.get(2).getName()).isEqualTo("nested1");
        List<Trace.DetailEntry> nestedDetails = details.get(2).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey11");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey12");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("b");
        assertThat(nestedDetails.get(2).getName()).isEqualTo("subnested1");
        List<Trace.DetailEntry> subNestedDetails = nestedDetails.get(2).getChildEntryList();
        assertThat(subNestedDetails.get(0).getName()).isEqualTo("subnestedkey1");
        assertThat(subNestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(subNestedDetails.get(1).getName()).isEqualTo("subnestedkey2");
        assertThat(subNestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("b");
        assertThat(details.get(3).getName()).isEqualTo("nested2");
        nestedDetails = details.get(3).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey21");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey22");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("b");

        assertThat(header.getTransactionName()).isEqualTo("basic test");
        assertThat(header.getRootTimer().getName()).isEqualTo("level one");
        assertThat(header.getRootTimer().getChildTimerList()).hasSize(1);
        assertThat(header.getRootTimer().getChildTimerList().get(0).getName())
                .isEqualTo("level two");
        Trace.Timer levelTwoTimer = header.getRootTimer().getChildTimerList().get(0);
        assertThat(levelTwoTimer.getChildTimerList()).hasSize(1);
        assertThat(levelTwoTimer.getChildTimerList().get(0).getName()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.getChildTimerList().get(0);
        assertThat(levelThreeTimer.getChildTimerList()).hasSize(1);
        assertThat(levelThreeTimer.getChildTimerList().get(0).getName()).isEqualTo("level four");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry2 = entries.get(0);
        assertThat(entry2.getMessage()).isEqualTo("Level Two");
        details = entry2.getDetailEntryList();
        assertThat(details).hasSize(2);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getSval()).isEqualTo("ax");
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getSval()).isEqualTo("bx");
        List<Trace.Entry> childEntries2 = entry2.getChildEntryList();
        assertThat(childEntries2).hasSize(1);
        Trace.Entry entry3 = childEntries2.get(0);
        assertThat(entry3.getMessage()).isEqualTo("Level Three");
        details = entry3.getDetailEntryList();
        assertThat(details).hasSize(2);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getSval()).isEqualTo("axy");
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getSval()).isEqualTo("bxy");
        // there's no way offsetNanos should be 0
        assertThat(entry3.getStartOffsetNanos()).isGreaterThan(0);
        List<Trace.Entry> childEntries3 = entry3.getChildEntryList();
        assertThat(childEntries3).hasSize(1);
        Trace.Entry entry4 = childEntries3.get(0);
        assertThat(entry4.getMessage()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadDetailMapWithBooleans() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithBooleans.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        assertThat(details).hasSize(4);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getBval()).isEqualTo(false);
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getBval()).isEqualTo(true);
        assertThat(details.get(2).getName()).isEqualTo("nested1");
        List<Trace.DetailEntry> nestedDetails = details.get(2).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey11");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getBval()).isEqualTo(false);
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey12");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getBval()).isEqualTo(true);
        assertThat(nestedDetails.get(2).getName()).isEqualTo("subnested1");
        List<Trace.DetailEntry> subNestedDetails = nestedDetails.get(2).getChildEntryList();
        assertThat(subNestedDetails.get(0).getName()).isEqualTo("subnestedkey1");
        assertThat(subNestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(0).getValueList().get(0).getBval()).isEqualTo(false);
        assertThat(subNestedDetails.get(1).getName()).isEqualTo("subnestedkey2");
        assertThat(subNestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(1).getValueList().get(0).getBval()).isEqualTo(true);
        assertThat(details.get(3).getName()).isEqualTo("nested2");
        nestedDetails = details.get(3).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey21");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getBval()).isEqualTo(false);
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey22");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getBval()).isEqualTo(true);
    }

    @Test
    public void shouldReadDetailMapWithNumbers() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNumbers.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        assertThat(details).hasSize(4);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getDval()).isEqualTo(5.0);
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getDval()).isEqualTo(5.5);
        assertThat(details.get(2).getName()).isEqualTo("nested1");
        List<Trace.DetailEntry> nestedDetails = details.get(2).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey11");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getDval()).isEqualTo(5.0);
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey12");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getDval()).isEqualTo(5.5);
        assertThat(nestedDetails.get(2).getName()).isEqualTo("subnested1");
        List<Trace.DetailEntry> subNestedDetails = nestedDetails.get(2).getChildEntryList();
        assertThat(subNestedDetails.get(0).getName()).isEqualTo("subnestedkey1");
        assertThat(subNestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(0).getValueList().get(0).getDval()).isEqualTo(5.0);
        assertThat(subNestedDetails.get(1).getName()).isEqualTo("subnestedkey2");
        assertThat(subNestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(1).getValueList().get(0).getDval()).isEqualTo(5.5);
        assertThat(details.get(3).getName()).isEqualTo("nested2");
        nestedDetails = details.get(3).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey21");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getDval()).isEqualTo(5.0);
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey22");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getDval()).isEqualTo(5.5);
    }

    @Test
    public void shouldReadDetailMapWithBadType() throws Exception {
        // given
        for (int i = 0; i < 4; i++) {
            container.addExpectedLogMessage("org.glowroot.agent.model.DetailMapWriter",
                    "detail map has unexpected value type: java.io.File");
        }
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithBadType.class);
        // then
        assertThat(trace.getHeader().getHeadline()).isEqualTo("Level One");
        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        assertThat(details).hasSize(4);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(details.get(1).getName()).isEqualTo("arg2");
        assertThat(details.get(1).getValueList()).hasSize(1);
        assertThat(details.get(1).getValueList().get(0).getSval()).isEqualTo("x");
        assertThat(details.get(2).getName()).isEqualTo("nested1");
        List<Trace.DetailEntry> nestedDetails = details.get(2).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey11");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey12");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("x");
        assertThat(nestedDetails.get(2).getName()).isEqualTo("subnested1");
        List<Trace.DetailEntry> subNestedDetails = nestedDetails.get(2).getChildEntryList();
        assertThat(subNestedDetails.get(0).getName()).isEqualTo("subnestedkey1");
        assertThat(subNestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(subNestedDetails.get(1).getName()).isEqualTo("subnestedkey2");
        assertThat(subNestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(subNestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("x");
        assertThat(details.get(3).getName()).isEqualTo("nested2");
        nestedDetails = details.get(3).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey21");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getSval()).isEqualTo("a");
        assertThat(nestedDetails.get(1).getName()).isEqualTo("nestedkey22");
        assertThat(nestedDetails.get(1).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(1).getValueList().get(0).getSval()).isEqualTo("x");
    }

    @Test
    public void shouldReadDetailMapWithNullKey() throws Exception {
        // given
        container.addExpectedLogMessage("org.glowroot.agent.model.DetailMapWriter",
                "detail map has null key");
        container.addExpectedLogMessage("org.glowroot.agent.model.DetailMapWriter",
                "detail map has null key");
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNullKey.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("Level One");

        List<Trace.DetailEntry> details = trace.getHeader().getDetailEntryList();
        assertThat(details).hasSize(2);
        assertThat(details.get(0).getName()).isEqualTo("arg1");
        assertThat(details.get(0).getValueList()).hasSize(1);
        assertThat(details.get(0).getValueList().get(0).getSval())
                .isEqualTo("useArg2AsKeyAndValue");
        assertThat(details.get(1).getName()).isEqualTo("nested1");
        List<Trace.DetailEntry> nestedDetails = details.get(1).getChildEntryList();
        assertThat(nestedDetails.get(0).getName()).isEqualTo("nestedkey11");
        assertThat(nestedDetails.get(0).getValueList()).hasSize(1);
        assertThat(nestedDetails.get(0).getValueList().get(0).getSval())
                .isEqualTo("useArg2AsKeyAndValue");
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }

    public static class ShouldGenerateTraceWithBooleans implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(false, true);
        }
    }

    public static class ShouldGenerateTraceWithNumbers implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(5, 5.5);
        }
    }

    public static class ShouldGenerateTraceWithNulls implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(5, null);
        }
    }

    public static class ShouldGenerateTraceWithBadType implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", new File("x"));
        }
    }

    public static class ShouldGenerateTraceWithNullKey implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("useArg2AsKeyAndValue", null);
        }
    }

    public static class ShouldGenerateTraceWithBadKeyType implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("useArg2AsKeyAndValue", new File("x"));
        }
    }
}
