/*
 * Copyright 2013-2016 the original author or authors.
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
import org.glowroot.agent.tests.app.AAA;
import org.glowroot.agent.tests.app.ParamObject;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginConfiguredInstrumentationIT {

    protected static Container container;

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
    public void shouldExecute1() throws Exception {
        // when
        Trace trace = container.execute(ShouldExecuteAAA.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getTransactionName()).isEqualTo("abc zzz");
        assertThat(header.getUser()).isEqualTo("uzzz");
        List<Trace.Attribute> attributes = header.getAttributeList();
        assertThat(attributes).hasSize(2);
        assertThat(attributes.get(0).getName()).isEqualTo("View");
        assertThat(attributes.get(0).getValueList()).containsExactly("vabc");
        assertThat(attributes.get(1).getName()).isEqualTo("Z");
        assertThat(attributes.get(1).getValueList()).containsExactly("zabc");
        assertThat(header.getEntryCount()).isZero();
    }

    public static class ShouldExecuteAAA implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            new AAA().execute("abc", new ParamObject("zzz"));
        }
    }
}
