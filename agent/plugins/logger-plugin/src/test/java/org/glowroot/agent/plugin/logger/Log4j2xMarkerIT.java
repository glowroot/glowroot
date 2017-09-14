/*
 * Copyright 2016-2017 the original author or authors.
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
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

public class Log4j2xMarkerIT {

    private static final String PLUGIN_ID = "logger";

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
                .isEqualTo("log warn: " + ShouldLog.logger.getName() + " - def");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: " + ShouldLog.logger.getName() + " - efg");

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
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_t");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: " + ShouldLogWithThrowable.logger.getName() + " - def_t");
        assertThat(entry.getError().getMessage()).isEqualTo("456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: " + ShouldLogWithThrowable.logger.getName() + " - efg_t");
        assertThat(entry.getError().getMessage()).isEqualTo("567");
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
                "log warn: " + ShouldLogWithNullThrowable.logger.getName() + " - def_tnull");
        assertThat(entry.getError().getMessage()).isEqualTo("def_tnull");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: " + ShouldLogWithNullThrowable.logger.getName() + " - efg_tnull");
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
                "log warn: " + ShouldLogWithOneParameter.logger.getName() + " - def_1 d");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: " + ShouldLogWithOneParameter.logger.getName() + " - efg_1 e");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithOneParameterAndThrowable() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithOneParameterAndThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_1_t e");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log warn: "
                + ShouldLogWithOneParameterAndThrowable.logger.getName() + " - def_1_t d");
        assertThat(entry.getError().getMessage()).isEqualTo("456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error: "
                + ShouldLogWithOneParameterAndThrowable.logger.getName() + " - efg_1_t e");
        assertThat(entry.getError().getMessage()).isEqualTo("567");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

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
                "log warn: " + ShouldLogWithTwoParameters.logger.getName() + " - def_2 d e");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: " + ShouldLogWithTwoParameters.logger.getName() + " - efg_2 e f");

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
        assertThat(entry.getMessage()).isEqualTo("log warn: "
                + ShouldLogWithMoreThanTwoParameters.logger.getName() + " - def_3 d e f");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error: "
                + ShouldLogWithMoreThanTwoParameters.logger.getName() + " - efg_3 e f g");

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
        assertThat(entry.getMessage()).isEqualTo("log warn: "
                + ShouldLogWithParametersAndThrowable.logger.getName() + " - def_3_t d e f");
        assertThat(entry.getError().getMessage()).isEqualTo("456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("log error: "
                + ShouldLogWithParametersAndThrowable.logger.getName() + " - efg_3_t e f g");
        assertThat(entry.getError().getMessage()).isEqualTo("567");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = LogManager.getLogger(ShouldLog.class);
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
        private static final Logger logger = LogManager.getLogger(ShouldLogWithThrowable.class);
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
                LogManager.getLogger(ShouldLogWithNullThrowable.class);
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
                LogManager.getLogger(ShouldLogWithOneParameter.class);
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
                LogManager.getLogger(ShouldLogWithOneParameterAndThrowable.class);
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
                LogManager.getLogger(ShouldLogWithTwoParameters.class);
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
                LogManager.getLogger(ShouldLogWithMoreThanTwoParameters.class);
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
                LogManager.getLogger(ShouldLogWithParametersAndThrowable.class);
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
