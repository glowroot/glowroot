/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.plugin.logger;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests of the servlet plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Slf4jTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        if (isShaded()) {
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
        container.getConfigService().setPluginProperty("logger",
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLog.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isEqualTo("efg");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(1).getMessage().getText()).isEqualTo("log warn: def");
        assertThat(entries.get(2).getMessage().getText()).isEqualTo("log error: efg");
    }

    @Test
    public void testLogWithThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty("logger",
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isEqualTo("efg_t");
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_t");
        assertThat(warnEntry.getError().getText()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_t");
        assertThat(errorEntry.getError().getText()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    @Test
    public void testLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty("logger",
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithNullThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isEqualTo("efg_tnull");
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_tnull");
        assertThat(warnEntry.getError().getText()).isEqualTo("def_tnull");
        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_tnull");
        assertThat(errorEntry.getError().getText()).isEqualTo("efg_tnull");
    }

    @Test
    public void testLogWithOneParameter() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithOneParameter.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_1 d");
        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_1 e");
    }

    @Test
    public void testLogWithOneParameterAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithOneParameterAndThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isEqualTo("efg_1_t e");
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_1_t d");
        assertThat(warnEntry.getError().getText()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_1_t e");
        assertThat(errorEntry.getError().getText()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    @Test
    public void testLogWithTwoParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithTwoParameters.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_2 d e");
        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_2 e f");
    }

    @Test
    public void testLogWithMoreThanTwoParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithMoreThanTwoParameters.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_3 d e f");
        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_3 e f g");
    }

    @Test
    public void testLogWithParametersAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithParametersAndThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);

        TraceEntry warnEntry = entries.get(1);
        assertThat(warnEntry.getMessage().getText()).isEqualTo("log warn: def_3_t d e f");
        assertThat(warnEntry.getError().getText()).isEqualTo("456");
        assertThat(warnEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        TraceEntry errorEntry = entries.get(2);
        assertThat(errorEntry.getMessage().getText()).isEqualTo("log error: efg_3_t e f g");
        assertThat(errorEntry.getError().getText()).isEqualTo("567");
        assertThat(errorEntry.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static class ShouldLog implements AppUnderTest, TraceMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLog.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc");
            logger.debug("bcd");
            logger.info("cde");
            logger.warn("def");
            logger.error("efg");
        }
    }

    public static class ShouldLogWithThrowable implements AppUnderTest, TraceMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLogWithThrowable.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_t", new IllegalStateException("123"));
            logger.debug("bcd_t", new IllegalStateException("234"));
            logger.info("cde_t", new IllegalStateException("345"));
            logger.warn("def_t", new IllegalStateException("456"));
            logger.error("efg_t", new IllegalStateException("567"));
        }
    }

    public static class ShouldLogWithNullThrowable implements AppUnderTest, TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithNullThrowable.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_tnull", (Throwable) null);
            logger.debug("bcd_tnull", (Throwable) null);
            logger.info("cde_tnull", (Throwable) null);
            logger.warn("def_tnull", (Throwable) null);
            logger.error("efg_tnull", (Throwable) null);
        }
    }

    public static class ShouldLogWithOneParameter implements AppUnderTest, TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithOneParameter.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_1 {}", "a");
            logger.debug("bcd_1 {}", "b");
            logger.info("cde_1 {}", "c");
            logger.warn("def_1 {}", "d");
            logger.error("efg_1 {}", "e");
        }
    }

    public static class ShouldLogWithOneParameterAndThrowable implements AppUnderTest, TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithOneParameterAndThrowable.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_1_t {}", "a", new IllegalStateException("123"));
            logger.debug("bcd_1_t {}", "b", new IllegalStateException("234"));
            logger.info("cde_1_t {}", "c", new IllegalStateException("345"));
            logger.warn("def_1_t {}", "d", new IllegalStateException("456"));
            logger.error("efg_1_t {}", "e", new IllegalStateException("567"));
        }
    }

    public static class ShouldLogWithTwoParameters implements AppUnderTest, TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithTwoParameters.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_2 {} {}", "a", "b");
            logger.debug("bcd_2 {} {}", "b", "c");
            logger.info("cde_2 {} {}", "c", "d");
            logger.warn("def_2 {} {}", "d", "e");
            logger.error("efg_2 {} {}", "e", "f");
        }
    }

    public static class ShouldLogWithMoreThanTwoParameters implements AppUnderTest, TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithMoreThanTwoParameters.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_3 {} {} {}", "a", "b", "c");
            logger.debug("bcd_3 {} {} {}", "b", "c", "d");
            logger.info("cde_3 {} {} {}", "c", "d", "e");
            logger.warn("def_3 {} {} {}", "d", "e", "f");
            logger.error("efg_3 {} {} {}", "e", "f", "g");
        }
    }

    public static class ShouldLogWithParametersAndThrowable implements AppUnderTest,
            TraceMarker {
        private static final Logger logger =
                LoggerFactory.getLogger(ShouldLogWithParametersAndThrowable.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace("abc_3_t {} {} {}", "a", "b", "c", new IllegalStateException("123"));
            logger.debug("bcd_3_t {} {} {}", "b", "c", "d", new IllegalStateException("234"));
            logger.info("cde_3_t {} {} {}", "c", "d", "e", new IllegalStateException("345"));
            logger.warn("def_3_t {} {} {}", "d", "e", "f", new IllegalStateException("456"));
            logger.error("efg_3_t {} {} {}", "e", "f", "g", new IllegalStateException("567"));
        }
    }
}
