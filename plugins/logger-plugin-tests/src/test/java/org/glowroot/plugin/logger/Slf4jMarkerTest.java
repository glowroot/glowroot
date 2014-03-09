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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic tests of the servlet plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Slf4jMarkerTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        if (Slf4jTest.isShaded()) {
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
                "traceErrorOnErrorWithNoThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLog.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isEqualTo("efg");
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(1).getMessage().getText()).isEqualTo("log warn: def");
        assertThat(trace.getSpans().get(2).getMessage().getText()).isEqualTo("log error: efg");
    }

    @Test
    public void testLogWithThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty("logger",
                "traceErrorOnErrorWithNoThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isEqualTo("efg_t");
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_t");
        assertThat(warnSpan.getError().getText()).isEqualTo("456");
        assertThat(warnSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_t");
        assertThat(errorSpan.getError().getText()).isEqualTo("567");
        assertThat(errorSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    @Test
    public void testLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty("logger",
                "traceErrorOnErrorWithNoThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithNullThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isEqualTo("efg_tnull");
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_tnull");
        assertThat(warnSpan.getError().getText()).isEqualTo("def_tnull");
        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_tnull");
        assertThat(errorSpan.getError().getText()).isEqualTo("efg_tnull");
    }

    @Test
    public void testLogWithOneParameter() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithOneParameter.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_1 d");
        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_1 e");
    }

    @Test
    public void testLogWithOneParameterAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithOneParameterAndThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isEqualTo("efg_1_t e");
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_1_t d");
        assertThat(warnSpan.getError().getText()).isEqualTo("456");
        assertThat(warnSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_1_t e");
        assertThat(errorSpan.getError().getText()).isEqualTo("567");
        assertThat(errorSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    @Test
    public void testLogWithTwoParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithTwoParameters.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_2 d e");
        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_2 e f");
    }

    @Test
    public void testLogWithMoreThanTwoParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithMoreThanTwoParameters.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_3 d e f");
        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_3 e f g");
    }

    @Test
    public void testLogWithParametersAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldLogWithParametersAndThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);

        Span warnSpan = trace.getSpans().get(1);
        assertThat(warnSpan.getMessage().getText()).isEqualTo("log warn: def_3_t d e f");
        assertThat(warnSpan.getError().getText()).isEqualTo("456");
        assertThat(warnSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");

        Span errorSpan = trace.getSpans().get(2);
        assertThat(errorSpan.getMessage().getText()).isEqualTo("log error: efg_3_t e f g");
        assertThat(errorSpan.getError().getText()).isEqualTo("567");
        assertThat(errorSpan.getError().getException().getStackTrace().get(0))
                .contains("traceMarker");
    }

    public static class ShouldLog implements AppUnderTest, TraceMarker {
        private static final Logger logger = LoggerFactory.getLogger(ShouldLog.class);
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            logger.trace((Marker) null, "abc");
            logger.debug((Marker) null, "bcd");
            logger.info((Marker) null, "cde");
            logger.warn((Marker) null, "def");
            logger.error((Marker) null, "efg");
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
            logger.trace((Marker) null, "abc_t", new IllegalStateException("123"));
            logger.debug((Marker) null, "bcd_t", new IllegalStateException("234"));
            logger.info((Marker) null, "cde_t", new IllegalStateException("345"));
            logger.warn((Marker) null, "def_t", new IllegalStateException("456"));
            logger.error((Marker) null, "efg_t", new IllegalStateException("567"));
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
            logger.trace((Marker) null, "abc_tnull", (Throwable) null);
            logger.debug((Marker) null, "bcd_tnull", (Throwable) null);
            logger.info((Marker) null, "cde_tnull", (Throwable) null);
            logger.warn((Marker) null, "def_tnull", (Throwable) null);
            logger.error((Marker) null, "efg_tnull", (Throwable) null);
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
            logger.trace((Marker) null, "abc_1 {}", "a");
            logger.debug((Marker) null, "bcd_1 {}", "b");
            logger.info((Marker) null, "cde_1 {}", "c");
            logger.warn((Marker) null, "def_1 {}", "d");
            logger.error((Marker) null, "efg_1 {}", "e");
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
            logger.trace((Marker) null, "abc_1_t {}", "a", new IllegalStateException("123"));
            logger.debug((Marker) null, "bcd_1_t {}", "b", new IllegalStateException("234"));
            logger.info((Marker) null, "cde_1_t {}", "c", new IllegalStateException("345"));
            logger.warn((Marker) null, "def_1_t {}", "d", new IllegalStateException("456"));
            logger.error((Marker) null, "efg_1_t {}", "e", new IllegalStateException("567"));
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
            logger.trace((Marker) null, "abc_2 {} {}", "a", "b");
            logger.debug((Marker) null, "bcd_2 {} {}", "b", "c");
            logger.info((Marker) null, "cde_2 {} {}", "c", "d");
            logger.warn((Marker) null, "def_2 {} {}", "d", "e");
            logger.error((Marker) null, "efg_2 {} {}", "e", "f");
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
            logger.trace((Marker) null, "abc_3 {} {} {}", "a", "b", "c");
            logger.debug((Marker) null, "bcd_3 {} {} {}", "b", "c", "d");
            logger.info((Marker) null, "cde_3 {} {} {}", "c", "d", "e");
            logger.warn((Marker) null, "def_3 {} {} {}", "d", "e", "f");
            logger.error((Marker) null, "efg_3 {} {} {}", "e", "f", "g");
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
