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
package org.glowroot.agent.tests;

import java.io.File;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.Threads;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TimerWrapperMethodsIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        File baseDir = Files.createTempDir();
        File configFile = new File(baseDir, "config.json");
        Files.write("{\"advanced\":{\"timerWrapperMethods\":true}}", configFile, Charsets.UTF_8);
        container = Containers.create(baseDir);
        // capture one trace to warm up the system, otherwise sometimes there are delays in class
        // loading and the profiler captures too many or too few samples
        container.execute(ShouldGenerateTraceWithProfile.class);
        container.checkAndReset();
        container.getConfigService().updateAdvancedConfig(
                AdvancedConfigUpdate.newBuilder()
                        .setTimerWrapperMethods(ProtoOptional.of(true))
                        .build());
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
        container.getConfigService().updateTransactionConfig(
                TransactionConfigUpdate.newBuilder()
                        .setProfilingIntervalMillis(ProtoOptional.of(20))
                        .build());
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithProfile.class);
        // then
        // profiler should have captured about 10 stack traces
        assertThat(trace.getHeader().getProfileSampleCount()).isBetween(5L, 15L);
        assertThat(trace.getProfileTree().getTimerNameList()).containsExactly("mock trace marker");
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
