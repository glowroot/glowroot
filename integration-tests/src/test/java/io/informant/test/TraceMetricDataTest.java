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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;
import io.informant.testkit.TraceMarker;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceMetricDataTest {

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
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldReadTraceMetricData() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMetricData.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getMetrics().size()).isEqualTo(1);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
    }

    @Test
    public void shouldReadTraceMetricDataWithRootAndSameNested() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithRootAndSameNestedMetric.class);
        // then
        Trace trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.getMetrics().size()).isEqualTo(1);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
        assertThat(trace.getMetrics().get(0).getCount()).isEqualTo(1);
    }

    @Test
    public void shouldReadActiveTraceMetricData() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateActiveTraceWithMetricData.class);
                return null;
            }
        });
        // then
        Trace trace = container.getInformant().getActiveTrace(5000);
        assertThat(trace).isNotNull();
        assertThat(trace.getMetrics().size()).isEqualTo(1);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
        assertThat(trace.getMetrics().get(0).getCount()).isEqualTo(1);
        assertThat(trace.getMetrics().get(0).isActive()).isTrue();
        assertThat(trace.getMetrics().get(0).isMinActive()).isTrue();
        assertThat(trace.getMetrics().get(0).isMaxActive()).isTrue();
        // cleanup
        future.get();
        executorService.shutdown();
    }

    public static class ShouldGenerateTraceWithMetricData implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }

    public static class ShouldGenerateTraceWithRootAndSameNestedMetric implements AppUnderTest,
            TraceMarker {
        private int nestingLevel = 0;
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
            if (nestingLevel < 10) {
                nestingLevel++;
                traceMarker();
            }
        }
    }

    public static class ShouldGenerateActiveTraceWithMetricData implements AppUnderTest,
            TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(100);
        }
    }
}
