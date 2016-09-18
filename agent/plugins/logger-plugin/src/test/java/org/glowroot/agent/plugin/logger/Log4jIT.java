/*
 * Copyright 2014-2016 the original author or authors.
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

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
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

public class Log4jIT {

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
                .isEqualTo("log warn: o.g.a.p.logger.Log4jIT$ShouldLog - def");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.logger.Log4jIT$ShouldLog - efg");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.logger.Log4jIT$ShouldLog - fgh");

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
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.Log4jIT$ShouldLogWithThrowable - def_");

        assertThat(entry.getError().getMessage()).isEqualTo("456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.Log4jIT$ShouldLogWithThrowable - efg_");

        assertThat(entry.getError().getMessage()).isEqualTo("567");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.l.Log4jIT$ShouldLogWithThrowable - fgh_");

        assertThat(entry.getError().getMessage()).isEqualTo("678");
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
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.Log4jIT$ShouldLogWithNullThrowable - def_");

        assertThat(entry.getError().getMessage()).isEqualTo("def_");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.Log4jIT$ShouldLogWithNullThrowable - efg_");

        assertThat(entry.getError().getMessage()).isEqualTo("efg_");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.l.Log4jIT$ShouldLogWithNullThrowable - fgh_");

        assertThat(entry.getError().getMessage()).isEqualTo("fgh_");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithPriority() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithPriority.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg__");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.Log4jIT$ShouldLogWithPriority - def__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.Log4jIT$ShouldLogWithPriority - efg__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.l.Log4jIT$ShouldLogWithPriority - fgh__");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithPriorityAndThrowable() throws Exception {
        // when
        Trace trace = container.execute(ShouldLogWithPriorityAndThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg___");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndThrowable - def___");

        assertThat(entry.getError().getMessage()).isEqualTo("456_");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndThrowable - efg___");

        assertThat(entry.getError().getMessage()).isEqualTo("567_");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log fatal: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndThrowable - fgh___");

        assertThat(entry.getError().getMessage()).isEqualTo("678_");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithPriorityAndNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithPriorityAndNullThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg___null");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndNullThrowable - def___null");

        assertThat(entry.getError().getMessage()).isEqualTo("def___null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndNullThrowable - efg___null");

        assertThat(entry.getError().getMessage()).isEqualTo("efg___null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log fatal: o.g.a.p.l.Log4jIT$ShouldLogWithPriorityAndNullThrowable - fgh___null");

        assertThat(entry.getError().getMessage()).isEqualTo("fgh___null");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLog() throws Exception {
        // when
        Trace trace = container.execute(ShouldLocalizedLog.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg____");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.Log4jIT$ShouldLocalizedLog - def____");

        assertThat(entry.getError().getMessage()).isEqualTo("456__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.Log4jIT$ShouldLocalizedLog - efg____");

        assertThat(entry.getError().getMessage()).isEqualTo("567__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.l.Log4jIT$ShouldLocalizedLog - fgh____");

        assertThat(entry.getError().getMessage()).isEqualTo("678__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLocalizedLogWithNullThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg____null");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithNullThrowable - def____null");

        assertThat(entry.getError().getMessage()).isEqualTo("def____null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithNullThrowable - efg____null");

        assertThat(entry.getError().getMessage()).isEqualTo("efg____null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log fatal: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithNullThrowable - fgh____null");

        assertThat(entry.getError().getMessage()).isEqualTo("fgh____null");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLogWithParameters() throws Exception {
        // when
        Trace trace = container.execute(ShouldLocalizedLogWithParameters.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg____");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithParameters - def____");

        assertThat(entry.getError().getMessage()).isEqualTo("456__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithParameters - efg____");

        assertThat(entry.getError().getMessage()).isEqualTo("567__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log fatal: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithParameters - fgh____");

        assertThat(entry.getError().getMessage()).isEqualTo("678__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLogWithEmptyParameters() throws Exception {
        // when
        Trace trace = container.execute(ShouldLocalizedLogWithEmptyParameters.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg____");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warn: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithEmptyParameters - def____");

        assertThat(entry.getError().getMessage()).isEqualTo("456__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log error: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithEmptyParameters - efg____");

        assertThat(entry.getError().getMessage()).isEqualTo("567__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log fatal: o.g.a.p.l.Log4jIT$ShouldLocalizedLogWithEmptyParameters - fgh____");

        assertThat(entry.getError().getMessage()).isEqualTo("678__");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLogWithParametersAndNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLocalizedLogWithParametersAndNullThrowable.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg____null");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warn: o.g.a.p.l.Log4jIT"
                        + "$ShouldLocalizedLogWithParametersAndNullThrowable - def____null");

        assertThat(entry.getError().getMessage()).isEqualTo("def____null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log error: o.g.a.p.l.Log4jIT"
                        + "$ShouldLocalizedLogWithParametersAndNullThrowable - efg____null");

        assertThat(entry.getError().getMessage()).isEqualTo("efg____null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log fatal: o.g.a.p.l.Log4jIT"
                        + "$ShouldLocalizedLogWithParametersAndNullThrowable - fgh____null");

        assertThat(entry.getError().getMessage()).isEqualTo("fgh____null");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLog.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug("bcd");
            logger.info("cde");
            logger.warn("def");
            logger.error("efg");
            logger.fatal("fgh");
        }
    }

    public static class ShouldLogWithThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLogWithThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug("bcd_", new IllegalStateException("234"));
            logger.info("cde_", new IllegalStateException("345"));
            logger.warn("def_", new IllegalStateException("456"));
            logger.error("efg_", new IllegalStateException("567"));
            logger.fatal("fgh_", new IllegalStateException("678"));
        }
    }

    public static class ShouldLogWithNullThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLogWithNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.debug("bcd_", null);
            logger.info("cde_", null);
            logger.warn("def_", null);
            logger.error("efg_", null);
            logger.fatal("fgh_", null);
        }
    }

    public static class ShouldLogWithPriority implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLogWithPriority.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.log(null, "abc__");
            } catch (NullPointerException e) {
                // re-throw if it does not originate from log4j
                if (!e.getStackTrace()[0].getClassName().startsWith("org.apache.log4j.")) {
                    throw e;
                }
            }
            logger.log(Level.DEBUG, "bcd__");
            logger.log(Level.INFO, "cde__");
            logger.log(Level.WARN, "def__");
            logger.log(Level.ERROR, "efg__");
            logger.log(Level.FATAL, "fgh__");
        }
    }

    public static class ShouldLogWithPriorityAndThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithPriorityAndThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.log(null, "abc___", new IllegalStateException("123_"));
            } catch (NullPointerException e) {
                // re-throw if it does not originate from log4j
                if (!e.getStackTrace()[0].getClassName().startsWith("org.apache.log4j.")) {
                    throw e;
                }
            }
            logger.log(Level.DEBUG, "bcd___", new IllegalStateException("234_"));
            logger.log(Level.INFO, "cde___", new IllegalStateException("345_"));
            logger.log(Level.WARN, "def___", new IllegalStateException("456_"));
            logger.log(Level.ERROR, "efg___", new IllegalStateException("567_"));
            logger.log(Level.FATAL, "fgh___", new IllegalStateException("678_"));
        }
    }

    public static class ShouldLogWithPriorityAndNullThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithPriorityAndNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.log(Level.DEBUG, "bcd___null", null);
            logger.log(Level.INFO, "cde___null", null);
            logger.log(Level.WARN, "def___null", null);
            logger.log(Level.ERROR, "efg___null", null);
            logger.log(Level.FATAL, "fgh___null", null);
        }
    }

    public static class ShouldLocalizedLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLocalizedLog.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.l7dlog(null, "abc____", new IllegalStateException("123__"));
            } catch (NullPointerException e) {
                // re-throw if it does not originate from log4j
                if (!e.getStackTrace()[0].getClassName().startsWith("org.apache.log4j.")) {
                    throw e;
                }
            }
            logger.l7dlog(Level.DEBUG, "bcd____", new IllegalStateException("234__"));
            logger.l7dlog(Level.INFO, "cde____", new IllegalStateException("345__"));
            logger.l7dlog(Level.WARN, "def____", new IllegalStateException("456__"));
            logger.l7dlog(Level.ERROR, "efg____", new IllegalStateException("567__"));
            logger.l7dlog(Level.FATAL, "fgh____", new IllegalStateException("678__"));
        }
    }

    public static class ShouldLocalizedLogWithNullThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLocalizedLogWithNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.l7dlog(Level.DEBUG, "bcd____null", null);
            logger.l7dlog(Level.INFO, "cde____null", null);
            logger.l7dlog(Level.WARN, "def____null", null);
            logger.l7dlog(Level.ERROR, "efg____null", null);
            logger.l7dlog(Level.FATAL, "fgh____null", null);
        }
    }

    public static class ShouldLocalizedLogWithParameters
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLocalizedLogWithParameters.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.l7dlog(null, "abc____", new Object[] {"a", "b", "c"},
                        new IllegalStateException("123__"));
            } catch (NullPointerException e) {
                // re-throw if it does not originate from log4j
                if (!e.getStackTrace()[0].getClassName().startsWith("org.apache.log4j.")) {
                    throw e;
                }
            }
            logger.l7dlog(Level.DEBUG, "bcd____", new Object[] {"b", "c", "d"},
                    new IllegalStateException("234__"));
            logger.l7dlog(Level.INFO, "cde____", new Object[] {"c", "d", "e"},
                    new IllegalStateException("345__"));
            logger.l7dlog(Level.WARN, "def____", new Object[] {"d", "e", "f"},
                    new IllegalStateException("456__"));
            logger.l7dlog(Level.ERROR, "efg____", new Object[] {"e", "f", "g"},
                    new IllegalStateException("567__"));
            logger.l7dlog(Level.FATAL, "fgh____", new Object[] {"f", "g", "h"},
                    new IllegalStateException("678__"));
        }
    }

    public static class ShouldLocalizedLogWithEmptyParameters
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLocalizedLogWithEmptyParameters.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.l7dlog(null, "abc____", new Object[] {"a", "b", "c"},
                        new IllegalStateException("123__"));
            } catch (NullPointerException e) {
                // re-throw if it does not originate from log4j
                if (!e.getStackTrace()[0].getClassName().startsWith("org.apache.log4j.")) {
                    throw e;
                }
            }
            logger.l7dlog(Level.DEBUG, "bcd____", new Object[] {},
                    new IllegalStateException("234__"));
            logger.l7dlog(Level.INFO, "cde____", new Object[] {},
                    new IllegalStateException("345__"));
            logger.l7dlog(Level.WARN, "def____", new Object[] {},
                    new IllegalStateException("456__"));
            logger.l7dlog(Level.ERROR, "efg____", new Object[] {},
                    new IllegalStateException("567__"));
            logger.l7dlog(Level.FATAL, "fgh____", new Object[] {},
                    new IllegalStateException("678__"));
        }
    }

    public static class ShouldLocalizedLogWithParametersAndNullThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLocalizedLogWithParametersAndNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.l7dlog(Level.DEBUG, "bcd____null", new Object[] {"b_", "c_", "d_"}, null);
            logger.l7dlog(Level.INFO, "cde____null", new Object[] {"c_", "d_", "e_"}, null);
            logger.l7dlog(Level.WARN, "def____null", new Object[] {"d_", "e_", "f_"}, null);
            logger.l7dlog(Level.ERROR, "efg____null", new Object[] {"e_", "f_", "g_"}, null);
            logger.l7dlog(Level.FATAL, "fgh____null", new Object[] {"f_", "g_", "h_"}, null);
        }
    }
}
