/*
 * Copyright 2017 the original author or authors.
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
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JavaLoggingIT {

    private static final String PLUGIN_ID = "logger";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        Assume.assumeFalse(isBridged());
        Assume.assumeTrue(isShaded());
        container = Containers.createJavaagent();
    }

    private static boolean isBridged() {
        try {
            Class.forName("org.slf4j.bridge.SLF4JBridgeHandler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
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
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLog - cde");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warning: o.g.a.p.l.JavaLoggingIT$ShouldLog - def");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: o.g.a.p.l.JavaLoggingIT$ShouldLog - efg");

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
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithThrowable - cde_");

        assertThat(entry.getError().getMessage()).isEqualTo("345");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithThrowable - def_");

        assertThat(entry.getError().getMessage()).isEqualTo("456");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithThrowable - efg_");

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
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithNullThrowable - cde_");

        assertThat(entry.getError().getMessage()).isEmpty(); // populated only if level > WARNING

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo(
                        "log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithNullThrowable - def_");

        assertThat(entry.getError().getMessage()).isEqualTo("def_");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithNullThrowable - efg_");

        assertThat(entry.getError().getMessage()).isEqualTo("efg_");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithLogRecord() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithLogRecord.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg__");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log info: null - cde__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithLogRecord - def__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: null - efg__");

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
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriority - cde__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriority - def__");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriority - efg__");

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
                "log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndThrowable - cde___");

        assertThat(entry.getError().getMessage()).isEqualTo("345_");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndThrowable - def___");

        assertThat(entry.getError().getMessage()).isEqualTo("456_");
        assertThat(
                entry.getError().getException().getStackTraceElementList().get(0).getMethodName())
                        .isEqualTo("transactionMarker");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndThrowable - efg___");

        assertThat(entry.getError().getMessage()).isEqualTo("567_");
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
                "log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndNullThrowable"
                        + " - cde___null");

        assertThat(entry.getError().getMessage()).isEmpty(); // populated only if level > WARNING

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndNullThrowable"
                        + " - def___null");

        assertThat(entry.getError().getMessage()).isEqualTo("def___null");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithPriorityAndNullThrowable"
                        + " - efg___null");

        assertThat(entry.getError().getMessage()).isEqualTo("efg___null");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLogWithParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLogWithParameters.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("ghi_78_89");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLogWithParameters - efg_56_67");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo(
                        "log warning: o.g.a.p.l.JavaLoggingIT$ShouldLogWithParameters - fgh_67_78");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo(
                        "log severe: o.g.a.p.l.JavaLoggingIT$ShouldLogWithParameters - ghi_78_89");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLocalizedLogWithParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);

        // when
        Trace trace = container.execute(ShouldLocalizedLogWithParameters.class);

        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("abc_78_89");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log info: o.g.a.p.l.JavaLoggingIT$ShouldLocalizedLogWithParameters"
                        + " - abc_56_67");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log warning: o.g.a.p.l.JavaLoggingIT$ShouldLocalizedLogWithParameters"
                        + " - xyz_78_67");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("log severe: o.g.a.p.l.JavaLoggingIT$ShouldLocalizedLogWithParameters"
                        + " - abc_78_89");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = Logger.getLogger(ShouldLog.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.finest("wxy");
            logger.finer("xyz");
            logger.fine("abc");
            logger.config("bcd");
            logger.info("cde");
            logger.warning("def");
            logger.severe("efg");
        }
    }

    public static class ShouldLogWithThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithThrowable.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.log(Level.FINEST, "wxy_", new IllegalStateException("678"));
            logger.log(Level.FINER, "xyz_", new IllegalStateException("789"));
            logger.log(Level.FINE, "abc_", new IllegalStateException("123"));
            logger.log(Level.CONFIG, "bcd_", new IllegalStateException("234"));
            logger.log(Level.INFO, "cde_", new IllegalStateException("345"));
            logger.log(Level.WARNING, "def_", new IllegalStateException("456"));
            logger.log(Level.SEVERE, "efg_", new IllegalStateException("567"));
        }
    }

    public static class ShouldLogWithNullThrowable implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithNullThrowable.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.log(Level.FINEST, "wxy_", (Throwable) null);
            logger.log(Level.FINER, "xyz_", (Throwable) null);
            logger.log(Level.FINE, "abc_", (Throwable) null);
            logger.log(Level.CONFIG, "bcd_", (Throwable) null);
            logger.log(Level.INFO, "cde_", (Throwable) null);
            logger.log(Level.WARNING, "def_", (Throwable) null);
            logger.log(Level.SEVERE, "efg_", (Throwable) null);
        }
    }

    public static class ShouldLogWithPriority implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithPriority.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.log(null, "abc__");
            } catch (NullPointerException e) {
                // re-throw if it does not originate from JUL
                if (!e.getStackTrace()[0].getClassName().startsWith("java.util.logging.")) {
                    throw e;
                }
            }
            logger.log(Level.FINEST, "vwx__");
            logger.log(Level.FINER, "wxy__");
            logger.log(Level.FINE, "xyz__");
            logger.log(Level.CONFIG, "bcd__");
            logger.log(Level.INFO, "cde__");
            logger.log(Level.WARNING, "def__");
            logger.log(Level.SEVERE, "efg__");
        }
    }

    public static class ShouldLogWithPriorityAndThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithPriorityAndThrowable.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                logger.log(null, "abc___", new IllegalStateException("123_"));
            } catch (NullPointerException e) {
                // re-throw if it does not originate from JUL
                if (!e.getStackTrace()[0].getClassName().startsWith("java.util.logging.")) {
                    throw e;
                }
            }
            logger.log(Level.FINEST, "vwx___", new IllegalStateException("111_"));
            logger.log(Level.FINER, "wxy___", new IllegalStateException("222_"));
            logger.log(Level.FINE, "xwy___", new IllegalStateException("333_"));
            logger.log(Level.CONFIG, "bcd___", new IllegalStateException("234_"));
            logger.log(Level.INFO, "cde___", new IllegalStateException("345_"));
            logger.log(Level.WARNING, "def___", new IllegalStateException("456_"));
            logger.log(Level.SEVERE, "efg___", new IllegalStateException("567_"));
        }
    }

    public static class ShouldLogWithPriorityAndNullThrowable
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithPriorityAndNullThrowable.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.log(Level.FINEST, "vwx___null", (Throwable) null);
            logger.log(Level.FINER, "wxy___null", (Throwable) null);
            logger.log(Level.FINE, "xyz___null", (Throwable) null);
            logger.log(Level.CONFIG, "bcd___null", (Throwable) null);
            logger.log(Level.INFO, "cde___null", (Throwable) null);
            logger.log(Level.WARNING, "def___null", (Throwable) null);
            logger.log(Level.SEVERE, "efg___null", (Throwable) null);
        }
    }

    public static class ShouldLogWithParameters implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithParameters.class.getName());

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            logger.log(Level.FINEST, "abc_{0}_{1}", new Object[] {12, 23});
            logger.log(Level.FINER, "bcd_{0}_{1}", new Object[] {23, 34});
            logger.log(Level.FINE, "cde_{0}_{1}", new Object[] {34, 45});
            logger.log(Level.CONFIG, "def_{0}_{1}", new Object[] {45, 56});
            logger.log(Level.INFO, "efg_{0}_{1}", new Object[] {56, 67});
            logger.log(Level.WARNING, "fgh_{0}_{1}", new Object[] {67, 78});
            logger.log(Level.SEVERE, "ghi_{0}_{1}", new Object[] {78, 89});
        }
    }

    public static class ShouldLocalizedLogWithParameters
            implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLocalizedLogWithParameters.class.getName(), "julmsgs");

        @Override
        public void executeApp() {
            transactionMarker();
        }

        @Override
        public void transactionMarker() {
            logger.log(Level.FINEST, "log.message.key1", new Object[] {12, 23});
            logger.log(Level.FINER, "log.message.key2", new Object[] {23, 34});
            logger.log(Level.FINE, "log.message.key1", new Object[] {34, 45});
            logger.log(Level.CONFIG, "log.message.key2", new Object[] {45, 56});
            logger.log(Level.INFO, "log.message.key1", new Object[] {56, 67});
            logger.log(Level.WARNING, "log.message.key2", new Object[] {67, 78});
            logger.log(Level.SEVERE, "log.message.key1", new Object[] {78, 89});
        }
    }

    public static class ShouldLogWithLogRecord implements AppUnderTest, TransactionMarker {
        private static final Logger logger =
                Logger.getLogger(ShouldLogWithLogRecord.class.getName());
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.log(new LogRecord(Level.FINEST, "vwx__"));
            logger.log(new LogRecord(Level.FINER, "wxy__"));
            logger.log(new LogRecord(Level.FINE, "xyz__"));
            logger.log(new LogRecord(Level.CONFIG, "bcd__"));
            logger.log(new LogRecord(Level.INFO, "cde__"));
            LogRecord lr = new LogRecord(Level.WARNING, "def__");
            lr.setLoggerName(logger.getName()); // test logger name
            logger.log(lr);
            logger.log(new LogRecord(Level.SEVERE, "efg__"));
        }
    }
}
