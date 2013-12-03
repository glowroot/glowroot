/*
 * Copyright 2012-2013 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.TraceMarker;

import static org.fest.assertions.api.Assertions.assertThat;

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
        container.getConfigService().setStoreThresholdMillis(0);
        File dbFile = new File(dataDir, "glowroot.h2.db");
        // when
        container.executeAppUnderTest(GenerateLotsOfTraces.class);
        long preCompactionDbSize = dbFile.length();
        container.getConfigService().compactData();
        // then
        assertThat(dbFile.length()).isLessThan(preCompactionDbSize);
    }

    public static class GenerateLotsOfTraces implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                traceMarker();
            }
        }
        public void traceMarker() {}
    }
}
