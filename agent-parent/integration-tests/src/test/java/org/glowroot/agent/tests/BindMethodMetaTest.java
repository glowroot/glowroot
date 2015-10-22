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

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

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
        Trace trace = container.execute(ShouldCoverBindMethodMetas.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getMessage()).isEqualTo("Coverage get: false");
        assertThat(entries.get(1).getMessage()).isEqualTo("Coverage get: 100");
        assertThat(entries.get(2).getMessage()).isEqualTo("Coverage get: b");
        assertThat(entries.get(3).getMessage()).isEqualTo("Coverage get: 300");
        assertThat(entries.get(4).getMessage()).isEqualTo("Coverage get: 400");
        assertThat(entries.get(5).getMessage()).isEqualTo("Coverage get: 500");
        assertThat(entries.get(6).getMessage()).isEqualTo("Coverage get: 600.0");
        assertThat(entries.get(7).getMessage()).isEqualTo("Coverage get: 700.0");
        assertThat(entries.get(8).getMessage()).isEqualTo("Coverage get: [1, 2, 3]");
        assertThat(entries.get(9).getMessage()).isEqualTo("Coverage put: true");
        assertThat(entries.get(10).getMessage()).isEqualTo("Coverage put: 101");
        assertThat(entries.get(11).getMessage()).isEqualTo("Coverage put: c");
        assertThat(entries.get(12).getMessage()).isEqualTo("Coverage put: 303");
        assertThat(entries.get(13).getMessage()).isEqualTo("Coverage put: 404");
        assertThat(entries.get(14).getMessage()).isEqualTo("Coverage put: 505");
        assertThat(entries.get(15).getMessage()).isEqualTo("Coverage put: 606.0");
        assertThat(entries.get(16).getMessage()).isEqualTo("Coverage put: 707.0");
        assertThat(entries.get(17).getMessage()).isEqualTo("Coverage put: [7, 8, 9]");
    }

    public static class ShouldCoverBindMethodMetas implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
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
