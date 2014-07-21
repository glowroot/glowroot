/*
 * Copyright 2011-2014 the original author or authors.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.glowroot.container.config.OutlierProfilingConfig;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.UserTracingConfig;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.Trace.Existence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ProfilingTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
        // capture one trace to warm up the system, otherwise sometimes there are delays in class
        // loading and the profiler captures too many or too few samples
        OutlierProfilingConfig profilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateOutlierProfilingConfig(profilingConfig);
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
    public void shouldReadProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        OutlierProfilingConfig outlierProfilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        outlierProfilingConfig.setInitialDelayMillis(200);
        outlierProfilingConfig.setIntervalMillis(10);
        outlierProfilingConfig.setMaxSeconds(300);
        container.getConfigService().updateOutlierProfilingConfig(outlierProfilingConfig);
        ProfilingConfig profilingConfig = container.getConfigService().getProfilingConfig();
        profilingConfig.setTracePercentage(100);
        profilingConfig.setIntervalMillis(10);
        profilingConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.YES);
        assertThat(trace.getOutlierProfileExistence()).isEqualTo(Existence.NO);
        // profiler should have captured about 10 stack traces
        ProfileNode rootProfileNode = container.getTraceService().getProfile(trace.getId());
        assertThat(rootProfileNode.getSampleCount()).isBetween(5, 15);
    }

    @Test
    public void shouldReadUserProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        OutlierProfilingConfig outlierProfilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        outlierProfilingConfig.setInitialDelayMillis(200);
        outlierProfilingConfig.setIntervalMillis(10);
        container.getConfigService().updateOutlierProfilingConfig(outlierProfilingConfig);
        ProfilingConfig profilingConfig = container.getConfigService().getProfilingConfig();
        profilingConfig.setTracePercentage(0);
        profilingConfig.setIntervalMillis(10);
        profilingConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateProfilingConfig(profilingConfig);
        UserTracingConfig userTracingConfig = container.getConfigService().getUserTracingConfig();
        userTracingConfig.setUser("able");
        userTracingConfig.setStoreThresholdMillis(0);
        userTracingConfig.setProfile(true);
        container.getConfigService().updateUserTracingConfig(userTracingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfileForAble.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.YES);
        assertThat(trace.getOutlierProfileExistence()).isEqualTo(Existence.NO);
        // profiler should have captured about 10 stack traces
        ProfileNode rootProfileNode = container.getTraceService().getProfile(trace.getId());
        assertThat(rootProfileNode.getSampleCount()).isBetween(5, 15);
    }

    // set profile store threshold to 0, and see if trace shows up in active list right away
    @Test
    public void shouldReadActiveProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        ProfilingConfig profilingConfig = container.getConfigService().getProfilingConfig();
        profilingConfig.setTracePercentage(100);
        profilingConfig.setIntervalMillis(10);
        profilingConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateProfilingConfig(profilingConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldWaitForInterrupt.class);
                return null;
            }
        });
        // then
        Trace trace = container.getTraceService().getActiveTrace(5, SECONDS);
        assertThat(trace).isNotNull();
        // cleanup
        container.interruptAppUnderTest();
        future.get();
        executorService.shutdown();
    }

    @Test
    public void shouldReadOutlierProfilingTree() throws Exception {
        // given
        OutlierProfilingConfig profilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateOutlierProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        ProfileNode rootProfileNode = container.getTraceService().getOutlierProfile(trace.getId());
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.NO);
        assertThat(trace.getOutlierProfileExistence()).isEqualTo(Existence.YES);
        // outlier profiler should have captured exactly 5 stack traces
        int sampleCount = rootProfileNode.getSampleCount();
        assertThat(sampleCount).isBetween(3, 7);
        assertThatTreeDoesNotContainSyntheticMetricWrapperMethods(rootProfileNode);
    }

    @Test
    public void shouldReadOutlierProfilingTreeWhenMaxSecondsIsZero() throws Exception {
        // given
        OutlierProfilingConfig profilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(50);
        profilingConfig.setMaxSeconds(0);
        container.getConfigService().updateOutlierProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        ProfileNode rootProfileNode = container.getTraceService().getOutlierProfile(trace.getId());
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.NO);
        assertThat(trace.getOutlierProfileExistence()).isEqualTo(Existence.YES);
        // outlier profiler should have captured exactly 1 stack trace
        assertThat(rootProfileNode.getSampleCount()).isEqualTo(1);
        assertThatTreeDoesNotContainSyntheticMetricWrapperMethods(rootProfileNode);
    }

    @Test
    public void shouldNotReadProfilingTreeWhenDisabled() throws Exception {
        // given
        OutlierProfilingConfig outlierProfilingConfig =
                container.getConfigService().getOutlierProfilingConfig();
        outlierProfilingConfig.setEnabled(false);
        outlierProfilingConfig.setInitialDelayMillis(60);
        outlierProfilingConfig.setIntervalMillis(10);
        container.getConfigService().updateOutlierProfilingConfig(outlierProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithProfile.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getProfileExistence()).isEqualTo(Existence.NO);
        assertThat(trace.getOutlierProfileExistence()).isEqualTo(Existence.NO);
    }

    private static void assertThatTreeDoesNotContainSyntheticMetricWrapperMethods(
            ProfileNode profileNode) {
        if (profileNode.getStackTraceElement().contains("$glowroot$metric$")) {
            throw new AssertionError("Not expecting synthetic metric methods but found: "
                    + profileNode.getStackTraceElement());
        }
        if (profileNode.getChildNodes() != null) {
            for (ProfileNode child : profileNode.getChildNodes()) {
                assertThatTreeDoesNotContainSyntheticMetricWrapperMethods(child);
            }
        }
    }

    public static class ShouldWaitForInterrupt implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            while (true) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    public static class ShouldGenerateTraceWithProfile implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Threads.moreAccurateSleep(105);
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
            pluginServices.setTraceUser("Able");
            Threads.moreAccurateSleep(105);
        }
    }
}
