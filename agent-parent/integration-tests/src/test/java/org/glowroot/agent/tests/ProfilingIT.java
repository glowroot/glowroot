/*
 * Copyright 2011-2015 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.Threads;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.model.ConfigUpdate.OptionalStringList;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate;
import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ProfilingIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
        // capture one header to warm up the system, otherwise sometimes there are delays in class
        // loading and the profiler captures too many or too few samples
        container.execute(ShouldGenerateTraceWithProfile.class);
        container.checkAndReset();
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
        setProfilingIntervalMillis(20);
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithProfile.class);
        // then
        // profiler should have captured about 10 stack traces
        assertThat(trace.getHeader().getProfileSampleCount()).isBetween(5L, 15L);
    }

    @Test
    public void shouldNotReadProfile() throws Exception {
        setProfilingIntervalMillis(0);
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithProfile.class);
        // then
        assertThat(trace.getHeader().getProfileSampleCount()).isZero();
    }

    @Test
    public void shouldReadUserRecordingProfile() throws Exception {
        // given
        UserRecordingConfigUpdate userRecordingConfig = UserRecordingConfigUpdate.newBuilder()
                .setUsers(OptionalStringList.newBuilder().addValue("able").build())
                .setProfilingIntervalMillis(ProtoOptional.of(20))
                .build();
        container.getConfigService().updateUserRecordingConfig(userRecordingConfig);
        setProfilingIntervalMillis(0);
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithProfileForAble.class);
        // then
        assertThat(trace.getHeader().getProfileSampleCount()).isBetween(5L, 15L);
        // profiler should have captured about 10 stack traces
    }

    @Test
    public void shouldNotReadUserRecordingProfile() throws Exception {
        // given
        UserRecordingConfigUpdate userRecordingConfig = UserRecordingConfigUpdate.newBuilder()
                .setUsers(OptionalStringList.newBuilder().addValue("baker").build())
                .setProfilingIntervalMillis(ProtoOptional.of(20))
                .build();
        container.getConfigService().updateUserRecordingConfig(userRecordingConfig);
        setProfilingIntervalMillis(0);
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithProfileForAble.class);
        // then
        assertThat(trace.getHeader().getProfileSampleCount()).isZero();
    }

    private static void setProfilingIntervalMillis(int millis) throws Exception {
        container.getConfigService().updateTransactionConfig(
                TransactionConfigUpdate.newBuilder()
                        .setProfilingIntervalMillis(ProtoOptional.of(millis))
                        .build());
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

    public static class ShouldGenerateTraceWithProfileForAble
            implements AppUnderTest, TransactionMarker {
        private static final TransactionService transactionService = Agent.getTransactionService();
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            // normally the plugin/aspect should set the user, this is just a shortcut for test
            transactionService.setTransactionUser("Able");
            Threads.moreAccurateSleep(200);
        }
    }
}
