/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.config.AdvancedConfig;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.it.harness.impl.LocalContainer;
import org.glowroot.agent.it.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ImmediatePartialStoreTest {

    private static File baseDir;
    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        container = JavaagentContainer.createWithFileDb(baseDir);
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
    public void shouldReadImmediatePartialStoreTrace() throws Exception {
        // given
        AdvancedConfig advancedConfig = container.getConfigService().getAdvancedConfig();
        advancedConfig.setImmediatePartialStoreThresholdSeconds(1);
        container.getConfigService().updateAdvancedConfig(advancedConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    container.executeAppUnderTest(ShouldGenerateActiveTrace.class);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return null;
            }
        });
        // give time for partial store to occur
        // (this has been source of sporadic failures on slow travis builds)
        Thread.sleep(10000);
        // interrupt trace which will then call System.exit() to kill jvm without completing trace
        container.interruptAppUnderTest();
        ((JavaagentContainer) container).cleanup();
        // give jvm a second to shut down
        Thread.sleep(1000);
        container = LocalContainer.createWithFileDb(baseDir);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header).isNotNull();
        assertThat(header.partial().or(false)).isTrue();
        // cleanup
        executorService.shutdown();
    }

    public static class ShouldGenerateActiveTrace implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                System.exit(123);
            }
        }
    }
}
