/*
 * Copyright 2014-2019 the original author or authors.
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
package org.glowroot.agent.plugin.logger;

import java.util.Iterator;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class LogbackMarkerIT {

    private static final String PLUGIN_ID = "logger";

    // logback 0.9.20 or prior
    private static final boolean OLD_LOGBACK;

    private static Container container;

    static {
        OLD_LOGBACK = Boolean.getBoolean("glowroot.test.oldLogback");
    }

    @BeforeAll
    public static void setUp() throws Exception {
        // unshaded doesn't work because glowroot loads slf4j classes before the Weaver is
        // registered, so the slf4j classes don't have a chance to get woven
        Assumptions.assumeTrue(LogbackIT.isShaded());
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testLog() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLog.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.LogbackMarkerIT$ShouldLog - def");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.LogbackMarkerIT$ShouldLog - efg");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage())
                .isEqualTo("java.lang.IllegalStateException: 567");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithThrowable - def_t");

        assertThat(entry.getError().getMessage()).isEqualTo("java.lang.IllegalStateException: 456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithThrowable - efg_t");

        assertThat(entry.getError().getMessage()).isEqualTo("java.lang.IllegalStateException: 567");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithNullThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_tnull");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithNullThrowable - def_tnull");

        assertThat(entry.getError().getMessage()).isEqualTo("def_tnull");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithNullThrowable - efg_tnull");

        assertThat(entry.getError().getMessage()).isEqualTo("efg_tnull");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithOneParameter() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithOneParameter.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithOneParameter - def_1 d");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithOneParameter - efg_1 e");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithOneParameterAndThrowable() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithOneParameterAndThrowable.class);

        // then
        if (!OLD_LOGBACK) {
            assertThat(trace.getHeader().getError().getMessage())
                    .isEqualTo("java.lang.IllegalStateException: 567");
        }

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log warn:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithOneParameterAndThrowable - def_1_t d");

        if (OLD_LOGBACK) {
            assertThat(entry.getError().getMessage()).isEqualTo("def_1_t d");
        } else {
            assertThat(entry.getError().getMessage())
                    .isEqualTo("java.lang.IllegalStateException: 456");
            assertThat(entry.getError().getException().getStackTraceElementList().get(0)
                    .getMethodName()).isEqualTo("transactionMarker");
        }

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithOneParameterAndThrowable - efg_1_t e");

        if (OLD_LOGBACK) {
            assertThat(entry.getError().getMessage()).isEqualTo("efg_1_t e");
        } else {
            assertThat(entry.getError().getMessage())
                    .isEqualTo("java.lang.IllegalStateException: 567");
            assertThat(entry.getError().getException().getStackTraceElementList().get(0)
                    .getMethodName()).isEqualTo("transactionMarker");
        }

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithTwoParameters() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithTwoParameters.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithTwoParameters - def_2 d e");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.LogbackMarkerIT$ShouldLogWithTwoParameters - efg_2 e f");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithMoreThanTwoParameters() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithMoreThanTwoParameters.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log warn:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithMoreThanTwoParameters - def_3 d e f");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithMoreThanTwoParameters - efg_3 e f g");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithParametersAndThrowable() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithParametersAndThrowable.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log warn:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithParametersAndThrowable - def_3_t d e f");

        if (OLD_LOGBACK) {
            assertThat(entry.getError().getMessage()).isEqualTo("def_3_t d e f");
        } else {
            assertThat(entry.getError().getMessage())
                    .isEqualTo("java.lang.IllegalStateException: 456");
            assertThat(entry.getError().getException().getStackTraceElementList().get(0)
                    .getMethodName()).isEqualTo("transactionMarker");
        }

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error:"
                + " o.g.a.p.l.LogbackMarkerIT$ShouldLogWithParametersAndThrowable - efg_3_t e f g");

        if (OLD_LOGBACK) {
            assertThat(entry.getError().getMessage()).isEqualTo("efg_3_t e f g");
        } else {
            assertThat(entry.getError().getMessage())
                    .isEqualTo("java.lang.IllegalStateException: 567");
            assertThat(entry.getError().getException().getStackTraceElementList().get(0)
                    .getMethodName()).isEqualTo("transactionMarker");
        }

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLog.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd");
            logger.info((Marker) null, "cde");
            logger.warn((Marker) null, "def");
            logger.error((Marker) null, "efg");
        }
    }

    public static class ShouldLogWithThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLogWithThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_t", new IllegalStateException("234"));
            logger.info((Marker) null, "cde_t", new IllegalStateException("345"));
            logger.warn((Marker) null, "def_t", new IllegalStateException("456"));
            logger.error((Marker) null, "efg_t", new IllegalStateException("567"));
        }
    }

    public static class ShouldLogWithNullThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_tnull", (Throwable) null);
            logger.info((Marker) null, "cde_tnull", (Throwable) null);
            logger.warn((Marker) null, "def_tnull", (Throwable) null);
            logger.error((Marker) null, "efg_tnull", (Throwable) null);
        }
    }

    public static class ShouldLogWithOneParameter implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithOneParameter.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_1 {}", "b");
            logger.info((Marker) null, "cde_1 {}", "c");
            logger.warn((Marker) null, "def_1 {}", "d");
            logger.error((Marker) null, "efg_1 {}", "e");
        }
    }

    public static class ShouldLogWithOneParameterAndThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithOneParameterAndThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_1_t {}", "b", new IllegalStateException("234"));
            logger.info((Marker) null, "cde_1_t {}", "c", new IllegalStateException("345"));
            logger.warn((Marker) null, "def_1_t {}", "d", new IllegalStateException("456"));
            logger.error((Marker) null, "efg_1_t {}", "e", new IllegalStateException("567"));
        }
    }

    public static class ShouldLogWithTwoParameters implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithTwoParameters.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_2 {} {}", "b", "c");
            logger.info((Marker) null, "cde_2 {} {}", "c", "d");
            logger.warn((Marker) null, "def_2 {} {}", "d", "e");
            logger.error((Marker) null, "efg_2 {} {}", "e", "f");
        }
    }

    public static class ShouldLogWithMoreThanTwoParameters
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithMoreThanTwoParameters.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_3 {} {} {}", new Object[] {"b", "c", "d"});
            logger.info((Marker) null, "cde_3 {} {} {}", new Object[] {"c", "d", "e"});
            logger.warn((Marker) null, "def_3 {} {} {}", new Object[] {"d", "e", "f"});
            logger.error((Marker) null, "efg_3 {} {} {}", new Object[] {"e", "f", "g"});
        }
    }

    public static class ShouldLogWithParametersAndThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithParametersAndThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug((Marker) null, "bcd_3_t {} {} {}", new Object[] {"b", "c", "d",
                    new IllegalStateException("234")});
            logger.info((Marker) null, "cde_3_t {} {} {}", new Object[] {"c", "d", "e",
                    new IllegalStateException("345")});
            logger.warn((Marker) null, "def_3_t {} {} {}", new Object[] {"d", "e", "f",
                    new IllegalStateException("456")});
            logger.error((Marker) null, "efg_3_t {} {} {}", new Object[] {"e", "f", "g",
                    new IllegalStateException("567")});
        }
    }
}
