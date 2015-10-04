/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.tests;

import java.io.File;

import com.google.common.base.Strings;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.transaction.TransactionService;

import static org.assertj.core.api.Assertions.assertThat;

public class DataSourceCompactTest {

    private static File baseDir;
    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        container = Containers.createWithFileDb(baseDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(baseDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCompact() throws Exception {
        // given
        File dbFile = new File(baseDir, "data/data.h2.db");
        // when
        container.addExpectedLogMessage("org.glowroot.agent.impl.TransactionCollector",
                "not storing a trace because of an excessive backlog");
        container.executeAppUnderTest(GenerateLotsOfTraces.class);
        long preCompactionDbSize = dbFile.length();
        container.getAdminService().deleteAllData();
        container.getConfigService().compactData();
        // then
        assertThat(dbFile.length()).isLessThan(preCompactionDbSize);
    }

    public static class GenerateLotsOfTraces implements AppUnderTest, TransactionMarker {

        private static final TransactionService transactionService = Agent.getTransactionService();

        @Override
        public void executeApp() throws InterruptedException {
            for (int i = 0; i < 10000; i++) {
                transactionMarker();
            }
        }

        @Override
        public void transactionMarker() {
            // need to fill up h2 db enough or it won't compact
            transactionService.setTransactionName(Strings.repeat("a", 10000));
        }
    }
}
