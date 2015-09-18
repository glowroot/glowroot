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
package org.glowroot.tests.javaagent;

import java.io.File;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.Threads;
import org.glowroot.container.TransactionMarker;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.impl.JavaagentContainer;
import org.glowroot.container.trace.ProfileTree;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TimerWrapperMethodsTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        File baseDir = Files.createTempDir();
        File configFile = new File(baseDir, "config.json");
        Files.write("{\"ui\":{\"port\":0},\"advanced\":{\"timerWrapperMethods\":true}}}",
                configFile, Charsets.UTF_8);
        container = JavaagentContainer.createWithFileDb(baseDir);
        // capture one trace to warm up the system, otherwise sometimes there are delays in class
        // loading and the profiler captures too many or too few samples
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
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
    public void shouldReadProfile() throws Exception {
        // given
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        transactionConfig.setProfilingIntervalMillis(20);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.profileSampleCount()).isGreaterThan(0);
        // profiler should have captured about 10 stack traces
        ProfileTree profileTree = container.getTraceService().getProfile(header.id());
        assertThat(profileTree.unfilteredSampleCount()).isBetween(5L, 15L);
    }

    public static class ShouldGenerateTraceWithProfile implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            Threads.moreAccurateSleep(200);
        }
    }
}
