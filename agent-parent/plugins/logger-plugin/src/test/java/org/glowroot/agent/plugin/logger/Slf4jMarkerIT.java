/*
 * Copyright 2014-2015 the original author or authors.
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

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class Slf4jMarkerIT {

    private static final String PLUGIN_ID = "logger";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        if (Slf4jIT.isShaded()) {
            container = Containers.getSharedContainer();
        } else {
            // combination of unshaded and javaagent doesn't work because javaagent uses and loads
            // slf4j classes before the WeavingClassFileTransformer is registered, so the slf4j
            // classes don't have a chance to get woven
            container = Containers.getSharedLocalContainer();
        }
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
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage()).isEqualTo("log warn: def");
        assertThat(entries.get(1).getMessage()).isEqualTo("log error: efg");
    }

    @Test
    public void testLogWithThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        Trace trace = container.execute(ShouldLogWithThrowable.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_t");
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_t");
        assertThat(warnEntry.getError().getMessage()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");

        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_t");
        assertThat(errorEntry.getError().getMessage()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");
    }

    @Test
    public void testLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        Trace trace = container.execute(ShouldLogWithNullThrowable.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_tnull");
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_tnull");
        assertThat(warnEntry.getError().getMessage()).isEqualTo("def_tnull");
        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_tnull");
        assertThat(errorEntry.getError().getMessage()).isEqualTo("efg_tnull");
    }

    @Test
    public void testLogWithOneParameter() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldLogWithOneParameter.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_1 d");
        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_1 e");
    }

    @Test
    public void testLogWithOneParameterAndThrowable() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldLogWithOneParameterAndThrowable.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg_1_t e");
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_1_t d");
        assertThat(warnEntry.getError().getMessage()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");

        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_1_t e");
        assertThat(errorEntry.getError().getMessage()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");
    }

    @Test
    public void testLogWithTwoParameters() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldLogWithTwoParameters.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_2 d e");
        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_2 e f");
    }

    @Test
    public void testLogWithMoreThanTwoParameters() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldLogWithMoreThanTwoParameters.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_3 d e f");
        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_3 e f g");
    }

    @Test
    public void testLogWithParametersAndThrowable() throws Exception {
        // given
        // when
        Trace trace = container.execute(ShouldLogWithParametersAndThrowable.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.getMessage()).isEqualTo("log warn: def_3_t d e f");
        assertThat(warnEntry.getError().getMessage()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");

        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.getMessage()).isEqualTo("log error: efg_3_t e f g");
        assertThat(errorEntry.getError().getMessage()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTraceElementList().get(0)
                .getMethodName()).isEqualTo("transactionMarker");
    }

    @Test
    public void testPluginDisabled() throws Exception {
        // given
        container.getConfigService().disablePlugin(PLUGIN_ID);
        // when
        Trace trace = container.execute(ShouldLog.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isZero();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLog.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            logger.trace((Marker) null, "abc");
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
            logger.trace((Marker) null, "abc_t", new IllegalStateException("123"));
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
            logger.trace((Marker) null, "abc_tnull", (Throwable) null);
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
            logger.trace((Marker) null, "abc_1 {}", "a");
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
            logger.trace((Marker) null, "abc_1_t {}", "a", new IllegalStateException("123"));
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
            logger.trace((Marker) null, "abc_2 {} {}", "a", "b");
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
            logger.trace((Marker) null, "abc_3 {} {} {}", "a", "b", "c");
            logger.debug((Marker) null, "bcd_3 {} {} {}", "b", "c", "d");
            logger.info((Marker) null, "cde_3 {} {} {}", "c", "d", "e");
            logger.warn((Marker) null, "def_3 {} {} {}", "d", "e", "f");
            logger.error((Marker) null, "efg_3 {} {} {}", "e", "f", "g");
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
            logger.trace((Marker) null, "abc_3_t {} {} {}", "a", "b", "c",
                    new IllegalStateException("123"));
            logger.debug((Marker) null, "bcd_3_t {} {} {}", "b", "c", "d",
                    new IllegalStateException("234"));
            logger.info((Marker) null, "cde_3_t {} {} {}", "c", "d", "e",
                    new IllegalStateException("345"));
            logger.warn((Marker) null, "def_3_t {} {} {}", "d", "e", "f",
                    new IllegalStateException("456"));
            logger.error((Marker) null, "efg_3_t {} {} {}", "e", "f", "g",
                    new IllegalStateException("567"));
        }
    }
}
