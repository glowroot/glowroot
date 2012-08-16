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
import org.informantproject.testkit.Config.CoreProperties;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.TraceMarker;
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
        container.closeAndDeleteFiles();
    }

    @Test
    public void shouldReadTraceMetricData() throws Exception {
        // given
        CoreProperties coreProperties = container.getInformant().getCoreProperties();
        coreProperties.setThresholdMillis(0);
        container.getInformant().updateCoreProperties(coreProperties);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMetricData.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getMetrics().size()).isEqualTo(1);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
    }

    public static class ShouldGenerateTraceWithMetricData implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }
}
