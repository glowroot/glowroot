/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.test;

import static org.fest.assertions.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.informantproject.api.PluginServices;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Config.CoarseProfilingConfig;
import org.informantproject.testkit.Config.FineProfilingConfig;
import org.informantproject.testkit.Config.UserTracingConfig;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.Trace.MergedStackTreeNode;
import org.informantproject.testkit.TraceMarker;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ProfilingTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraceSnapshots();
    }

    @Test
    public void shouldReadCoarseProfilingTree() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        CoarseProfilingConfig profilingConfig = container.getInformant().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(100);
        profilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(profilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNotNull();
        // coarse profiler should have captured about 5 stack traces
        assertThat(trace.getCoarseMergedStackTree().getSampleCount()).isGreaterThan(0);
        assertThat(trace.getCoarseMergedStackTree().getSampleCount()).isLessThan(10);
        assertThatTreeDoesNotContainSyntheticMetricMethods(trace.getCoarseMergedStackTree());
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadFineProfilingTree() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(10000);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setPersistenceThresholdMillis(0);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 15 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThan(10);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThan(20);
    }

    @Test
    public void shouldReadFineUserProfilingTree() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(10000);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(0);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setPersistenceThresholdMillis(10000);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        UserTracingConfig userTracingConfig = container.getInformant().getUserTracingConfig();
        userTracingConfig.setUserId("able");
        userTracingConfig.setPersistenceThresholdMillis(0);
        userTracingConfig.setFineProfiling(true);
        container.getInformant().updateUserTracingConfig(userTracingConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTreeForAble.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getCoarseMergedStackTree()).isNull();
        assertThat(trace.getFineMergedStackTree()).isNotNull();
        // fine profiler should have captured about 15 stack traces
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isGreaterThan(10);
        assertThat(trace.getFineMergedStackTree().getSampleCount()).isLessThan(20);
    }

    // set fine persistence threshold to 0, and see if trace shows up in active list right away
    @Test
    public void shouldReadActiveFineProfilingTree() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(10000);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setPersistenceThresholdMillis(0);
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
        Trace trace = container.getInformant().getActiveTrace(5000);
        assertThat(trace).isNotNull();
        // cleanup
        future.get();
        executorService.shutdown();
    }

    @Test
    public void shouldNotReadProfilingTreeWhenDisabled() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setEnabled(false);
        coarseProfilingConfig.setInitialDelayMillis(100);
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
            Thread.sleep(150);
        }
    }

    public static class ShouldGenerateTraceWithMergedStackTreeForAble implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices = PluginServices
                .get("org.informantproject:informant-integration-tests");
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            // normally the plugin/aspect should set the user id, this is just a shortcut for test
            pluginServices.setUserId("able");
            Thread.sleep(150);
        }
    }
}
