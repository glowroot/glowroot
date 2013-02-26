/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.api.PluginServices;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.CoarseProfilingConfig;
import io.informant.testkit.FineProfilingConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;
import io.informant.testkit.Trace.MergedStackTreeNode;
import io.informant.testkit.TraceMarker;
import io.informant.testkit.UserConfig;
import io.informant.util.Threads;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ProfilingTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
        // capture one trace to warm up the system, otherwise sometimes there are delays in class
        // loading and the profiler captures too many or too few samples
        container.getInformant().setStoreThresholdMillis(0);
        CoarseProfilingConfig profilingConfig = container.getInformant().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(profilingConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldReadCoarseProfilingTree() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        CoarseProfilingConfig profilingConfig = container.getInformant().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNotNull();
        // coarse profiler should have captured exactly 5 stack traces
        assertThat(trace.getCoarseMergedStackTree().getSampleCount()).isEqualTo(5);
        assertThatTreeDoesNotContainSyntheticMetricMethods(trace.getCoarseMergedStackTree());
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadCoarseProfilingTreeWhenTotalSecondsIsZero() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        CoarseProfilingConfig profilingConfig = container.getInformant().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(60);
        profilingConfig.setIntervalMillis(10);
        profilingConfig.setTotalSeconds(0);
        container.getInformant().updateCoarseProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNotNull();
        // coarse profiler should have captured exactly 1 stack trace
        assertThat(trace.getCoarseMergedStackTree().getSampleCount()).isEqualTo(1);
        assertThatTreeDoesNotContainSyntheticMetricMethods(trace.getCoarseMergedStackTree());
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadFineProfilingTree() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(10000);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        coarseProfilingConfig.setTotalSeconds(300);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(0);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 10 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThan(9);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThan(11);
    }

    @Test
    public void shouldReadFineUserProfilingTree() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(10000);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(0);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(10000);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        UserConfig userConfig = container.getInformant().getUserConfig();
        userConfig.setUserId("able");
        userConfig.setStoreThresholdMillis(0);
        userConfig.setFineProfiling(true);
        container.getInformant().updateUserConfig(userConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTreeForAble.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 10 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThan(9);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThan(11);
    }

    // set fine store threshold to 0, and see if trace shows up in active list right away
    @Test
    public void shouldReadActiveFineProfilingTree() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(10000);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setStoreThresholdMillis(0);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
                return null;
            }
        });
        // then
        Trace trace = container.getInformant().getActiveTraceSummary(5000);
        assertThat(trace).isNotNull();
        // cleanup
        future.get();
        executorService.shutdown();
    }

    @Test
    public void shouldNotReadProfilingTreeWhenDisabled() throws Exception {
        // given
        container.getInformant().setStoreThresholdMillis(0);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setEnabled(false);
        coarseProfilingConfig.setInitialDelayMillis(60);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setEnabled(false);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    private static void assertThatTreeDoesNotContainSyntheticMetricMethods(
            MergedStackTreeNode mergedStackTree) {
        if (mergedStackTree.getStackTraceElement().contains("$informant$metric$")) {
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
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Threads.moreAccurateSleep(105);
        }
    }

    public static class ShouldGenerateTraceWithMergedStackTreeForAble implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("io.informant:informant-integration-tests");
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            // normally the plugin/aspect should set the user id, this is just a shortcut for test
            pluginServices.setUserId("able");
            Threads.moreAccurateSleep(105);
        }
    }
}
