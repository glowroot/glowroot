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

import checkers.nullness.quals.Nullable;
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
import org.glowroot.container.config.CoarseProfilingConfig;
import org.glowroot.container.config.FineProfilingConfig;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.UserOverridesConfig;
import org.glowroot.container.trace.MergedStackTreeNode;
import org.glowroot.container.trace.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.api.Assertions.assertThat;

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
        CoarseProfilingConfig profilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateCoarseProfilingConfig(profilingConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
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
    public void shouldReadCoarseProfilingTree() throws Exception {
        // given
        CoarseProfilingConfig profilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateCoarseProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNotNull();
        // coarse profiler should have captured exactly 5 stack traces
        int sampleCount = trace.getCoarseMergedStackTree().getSampleCount();
        assertThat(sampleCount).isGreaterThanOrEqualTo(3);
        assertThat(sampleCount).isLessThanOrEqualTo(7);
        assertThatTreeDoesNotContainSyntheticMetricMethods(trace.getCoarseMergedStackTree());
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadCoarseProfilingTreeWhenTotalSecondsIsZero() throws Exception {
        // given
        CoarseProfilingConfig profilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        profilingConfig.setTotalSeconds(0);
        container.getConfigService().updateCoarseProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNotNull();
        // coarse profiler should have captured exactly 1 stack trace
        assertThat(trace.getCoarseMergedStackTree().getSampleCount()).isEqualTo(1);
        assertThatTreeDoesNotContainSyntheticMetricMethods(trace.getCoarseMergedStackTree());
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadFineProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        CoarseProfilingConfig coarseProfilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        coarseProfilingConfig.setTotalSeconds(300);
        container.getConfigService().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getConfigService()
                .getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 10 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThanOrEqualTo(5);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThanOrEqualTo(15);
    }

    @Test
    public void shouldReadFineUserProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        CoarseProfilingConfig coarseProfilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getConfigService().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getConfigService()
                .getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(0);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        UserOverridesConfig userOverridesConfig =
                container.getConfigService().getUserOverridesConfig();
        userOverridesConfig.setUserId("able");
        userOverridesConfig.setStoreThresholdMillis(0);
        userOverridesConfig.setFineProfiling(true);
        container.getConfigService().updateUserOverridesConfig(userOverridesConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTreeForAble.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 10 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThanOrEqualTo(5);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThanOrEqualTo(15);
    }

    // set fine store threshold to 0, and see if trace shows up in active list right away
    @Test
    public void shouldReadActiveFineProfilingTree() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        FineProfilingConfig fineProfilingConfig =
                container.getConfigService().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
                return null;
            }
        });
        // then
        Trace trace = container.getTraceService().getActiveTraceSummary(5, SECONDS);
        assertThat(trace).isNotNull();
        // cleanup
        future.get();
        executorService.shutdown();
    }

    @Test
    public void shouldNotReadProfilingTreeWhenDisabled() throws Exception {
        // given
        CoarseProfilingConfig coarseProfilingConfig =
                container.getConfigService().getCoarseProfilingConfig();
        coarseProfilingConfig.setEnabled(false);
        coarseProfilingConfig.setInitialDelayMillis(60);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getConfigService().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getConfigService()
                .getFineProfilingConfig();
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    private static void assertThatTreeDoesNotContainSyntheticMetricMethods(
            MergedStackTreeNode mergedStackTree) {
        if (mergedStackTree.getStackTraceElement().contains("$glowroot$metric$")) {
            throw new AssertionError("Not expecting synthetic metric methods but found: "
                    + mergedStackTree.getStackTraceElement());
        }
        if (mergedStackTree.getChildNodes() != null) {
            for (MergedStackTreeNode child : mergedStackTree.getChildNodes()) {
                assertThatTreeDoesNotContainSyntheticMetricMethods(child);
            }
        }
    }

    public static class ShouldGenerateTraceWithMergedStackTree implements AppUnderTest,
            TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Threads.moreAccurateSleep(105);
        }
    }

    public static class ShouldGenerateTraceWithMergedStackTreeForAble implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            // normally the plugin/aspect should set the user id, this is just a shortcut for test
            pluginServices.setUserId("able");
            Threads.moreAccurateSleep(105);
        }
    }
}
