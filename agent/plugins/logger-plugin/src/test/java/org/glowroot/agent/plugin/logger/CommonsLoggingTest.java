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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.config.PluginConfig;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class CommonsLoggingTest {

    private static final String PLUGIN_ID = "logger";

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
    public void testLog() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLog.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().get().message()).isEqualTo("efg");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).message()).isEqualTo("log warn: def");
        assertThat(entries.get(1).message()).isEqualTo("log error: efg");
        assertThat(entries.get(2).message()).isEqualTo("log fatal: fgh");
    }

    @Test
    public void testLogWithThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithThrowable.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().get().message()).isEqualTo("efg_");
        assertThat(entries).hasSize(3);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.message()).isEqualTo("log warn: def_");
        assertThat(warnEntry.error().get().message()).isEqualTo("456");
        assertThat(warnEntry.error().get().exception().get().stackTraceElements().get(0))
                .contains("transactionMarker");

        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.message()).isEqualTo("log error: efg_");
        assertThat(errorEntry.error().get().message()).isEqualTo("567");
        assertThat(errorEntry.error().get().exception().get().stackTraceElements().get(0))
                .contains("transactionMarker");

        Trace.Entry fatalEntry = entries.get(2);
        assertThat(fatalEntry.message()).isEqualTo("log fatal: fgh_");
        assertThat(fatalEntry.error().get().message()).isEqualTo("678");
        assertThat(fatalEntry.error().get().exception().get().stackTraceElements().get(0))
                .contains("transactionMarker");
    }

    @Test
    public void testLogWithNullThrowable() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "traceErrorOnErrorWithoutThrowable", true);
        // when
        container.executeAppUnderTest(ShouldLogWithNullThrowable.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().get().message()).isEqualTo("efg_");
        assertThat(entries).hasSize(3);

        Trace.Entry warnEntry = entries.get(0);
        assertThat(warnEntry.message()).isEqualTo("log warn: def_");
        assertThat(warnEntry.error().get().message()).isEqualTo("def_");
        Trace.Entry errorEntry = entries.get(1);
        assertThat(errorEntry.message()).isEqualTo("log error: efg_");
        assertThat(errorEntry.error().get().message()).isEqualTo("efg_");
        Trace.Entry fatalEntry = entries.get(2);
        assertThat(fatalEntry.message()).isEqualTo("log fatal: fgh_");
        assertThat(fatalEntry.error().get().message()).isEqualTo("fgh_");
    }

    @Test
    public void testPluginDisabled() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setEnabled(false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(ShouldLog.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.entryCount()).isZero();
    }

    public static class ShouldLog implements AppUnderTest, TransactionMarker {
        private static final Log log = LogFactory.getLog(ShouldLog.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            log.trace("abc");
            log.debug("bcd");
            log.info("cde");
            log.warn("def");
            log.error("efg");
            log.fatal("fgh");
        }
    }

    public static class ShouldLogWithThrowable implements AppUnderTest, TransactionMarker {
        private static final Log log = LogFactory.getLog(ShouldLogWithThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            log.trace("abc_", new IllegalStateException("123"));
            log.debug("bcd_", new IllegalStateException("234"));
            log.info("cde_", new IllegalStateException("345"));
            log.warn("def_", new IllegalStateException("456"));
            log.error("efg_", new IllegalStateException("567"));
            log.fatal("fgh_", new IllegalStateException("678"));
        }
    }

    public static class ShouldLogWithNullThrowable implements AppUnderTest, TransactionMarker {
        private static final Log log = LogFactory.getLog(ShouldLogWithNullThrowable.class);
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            log.trace("abc_", null);
            log.debug("bcd_", null);
            log.info("cde_", null);
            log.warn("def_", null);
            log.error("efg_", null);
            log.fatal("fgh_", null);
        }
    }
}
