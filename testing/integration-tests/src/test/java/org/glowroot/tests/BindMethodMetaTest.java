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
package org.glowroot.tests;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class BindMethodMetaTest {

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
    public void shouldReadTraces() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCoverBindMethodMetas.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries.get(1).getMessage().getText()).isEqualTo("Coverage get: false");
        assertThat(entries.get(2).getMessage().getText()).isEqualTo("Coverage get: 100");
        assertThat(entries.get(3).getMessage().getText()).isEqualTo("Coverage get: b");
        assertThat(entries.get(4).getMessage().getText()).isEqualTo("Coverage get: 300");
        assertThat(entries.get(5).getMessage().getText()).isEqualTo("Coverage get: 400");
        assertThat(entries.get(6).getMessage().getText()).isEqualTo("Coverage get: 500");
        assertThat(entries.get(7).getMessage().getText()).isEqualTo("Coverage get: 600.0");
        assertThat(entries.get(8).getMessage().getText()).isEqualTo("Coverage get: 700.0");
        assertThat(entries.get(9).getMessage().getText()).isEqualTo("Coverage get: [1, 2, 3]");
        assertThat(entries.get(10).getMessage().getText()).isEqualTo("Coverage put: true");
        assertThat(entries.get(11).getMessage().getText()).isEqualTo("Coverage put: 101");
        assertThat(entries.get(12).getMessage().getText()).isEqualTo("Coverage put: c");
        assertThat(entries.get(13).getMessage().getText()).isEqualTo("Coverage put: 303");
        assertThat(entries.get(14).getMessage().getText()).isEqualTo("Coverage put: 404");
        assertThat(entries.get(15).getMessage().getText()).isEqualTo("Coverage put: 505");
        assertThat(entries.get(16).getMessage().getText()).isEqualTo("Coverage put: 606.0");
        assertThat(entries.get(17).getMessage().getText()).isEqualTo("Coverage put: 707.0");
        assertThat(entries.get(18).getMessage().getText()).isEqualTo("Coverage put: [7, 8, 9]");
    }

    public static class ShouldCoverBindMethodMetas implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }

        @Override
        public void traceMarker() {
            CoverAllTypes target = new CoverAllTypes();
            target.getBoolean();
            target.getByte();
            target.getChar();
            target.getShort();
            target.getInt();
            target.getLong();
            target.getFloat();
            target.getDouble();
            target.getArray();

            target.putBoolean(true);
            target.putByte((byte) 101);
            target.putChar('c');
            target.putShort((short) 303);
            target.putInt(404);
            target.putLong(505);
            target.putFloat(606);
            target.putDouble(707);
            target.putArray(new int[] {7, 8, 9});
        }
    }
}
