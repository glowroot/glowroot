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

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Config.CoarseProfilingConfig;
import org.informantproject.testkit.Config.CoreConfig;
import org.informantproject.testkit.Config.FineProfilingConfig;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.TraceMarker;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MergedStackTreeTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.closeAndDeleteFiles();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraces();
    }

    @Test
    public void shouldReadCoarseMergedStackTree() throws Exception {
        // given
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        container.getInformant().updateCoreConfig(coreConfig);
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
        assertThat(trace.getFineMergedStackTree()).isNull();
    }

    @Test
    public void shouldReadFineMergedStackTree() throws Exception {
        // given
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        container.getInformant().updateCoreConfig(coreConfig);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(200);
        coarseProfilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
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
    public void shouldNotReadMergedStackTreeWhenDisabled() throws Exception {
        // given
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        container.getInformant().updateCoreConfig(coreConfig);
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

    public static class ShouldGenerateTraceWithMergedStackTree implements AppUnderTest,
            TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(150);
        }
    }
}
