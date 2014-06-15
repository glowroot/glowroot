/*
 * Copyright 2012-2014 the original author or authors.
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

import java.io.File;

import com.google.common.base.Strings;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.PluginServices;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.TraceMarker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceCompactTest {

    private static File dataDir;
    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        dataDir = TempDirs.createTempDir("glowroot-test-datadir");
        container = Containers.createWithFileDb(dataDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(dataDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCompact() throws Exception {
        // given
        File dbFile = new File(dataDir, "glowroot.h2.db");
        // when
        container.addExpectedLogMessage("org.glowroot.collector.TraceCollectorImpl",
                "not storing a trace because of an excessive backlog");
        container.executeAppUnderTest(GenerateLotsOfTraces.class);
        long preCompactionDbSize = dbFile.length();
        container.getTraceService().deleteAll();
        container.getConfigService().compactData();
        // then
        assertThat(dbFile.length()).isLessThan(preCompactionDbSize);
    }

    public static class GenerateLotsOfTraces implements AppUnderTest, TraceMarker {

        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");

        @Override
        public void executeApp() throws InterruptedException {
            for (int i = 0; i < 10000; i++) {
                traceMarker();
            }
        }

        @Override
        public void traceMarker() {
            // need to fill up h2 db enough or it won't compact
            pluginServices.setTransactionName(Strings.repeat("a", 10000));
        }
    }
}
