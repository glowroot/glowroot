/*
 * Copyright 2013-2014 the original author or authors.
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
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginPointcutConfigTest {

    protected static Container container;

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
    public void shouldExecute1() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteAAA.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<Span> spans = container.getTraceService().getSpans(trace.getId());
        assertThat(spans).hasSize(1);
        assertThat(trace.getTransactionName()).isEqualTo("abc zzz");
        assertThat(trace.getUser()).isEqualTo("uzzz");
        assertThat(trace.getCustomAttributes().get("View")).containsExactly("vabc");
        assertThat(trace.getCustomAttributes().get("Z")).containsExactly("zabc");
    }

    public static class ShouldExecuteAAA implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            new AAA().execute("abc", new ParamObject("zzz"));
        }
    }
}
