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
package org.glowroot.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.PluginServices;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.Threads;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.UserRecordingConfig;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.Trace.Existence;

import static org.assertj.core.api.Assertions.assertThat;

public class ProfilingTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setProfilingIntervalMillis(20);
        container.getConfigService().updateGeneralConfig(generalConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.YES);
        // profiler should have captured about 10 stack traces
        ProfileNode rootProfileNode = container.getTraceService().getProfile(trace.getId());
        assertThat(rootProfileNode.getSampleCount()).isBetween(5, 15);
    }

    @Test
    public void shouldNotReadProfile() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setProfilingIntervalMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.NO);
    }

    @Test
    public void shouldReadUserRecordingProfile() throws Exception {
        // given
        UserRecordingConfig userRecordingConfig =
                container.getConfigService().getUserRecordingConfig();
        userRecordingConfig.setEnabled(true);
        userRecordingConfig.setUser("able");
        userRecordingConfig.setProfileIntervalMillis(20);
        container.getConfigService().updateUserRecordingConfig(userRecordingConfig);
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setProfilingIntervalMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfileForAble.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.YES);
        // profiler should have captured about 10 stack traces
        ProfileNode rootProfileNode = container.getTraceService().getProfile(trace.getId());
        assertThat(rootProfileNode.getSampleCount()).isBetween(5, 15);
    }

    @Test
    public void shouldNotReadUserRecordingProfile() throws Exception {
        // given
        UserRecordingConfig userRecordingConfig =
                container.getConfigService().getUserRecordingConfig();
        userRecordingConfig.setEnabled(true);
        userRecordingConfig.setUser("baker");
        userRecordingConfig.setProfileIntervalMillis(20);
        container.getConfigService().updateUserRecordingConfig(userRecordingConfig);
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setProfilingIntervalMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfileForAble.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.NO);
    }

    public static class ShouldGenerateTraceWithProfile implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Threads.moreAccurateSleep(200);
        }
    }

    public static class ShouldGenerateTraceWithProfileForAble implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            // normally the plugin/aspect should set the user, this is just a shortcut for test
            pluginServices.setTransactionUser("Able");
            Threads.moreAccurateSleep(200);
        }
    }
}
