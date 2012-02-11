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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.RootSpanMarker;
import org.informantproject.testkit.Trace;
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
        container = InformantContainer.newInstance();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Test
    public void shouldReadMergedStackTree() throws Exception {
        // given
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        coreConfiguration.setThresholdMillis(0);
        coreConfiguration.setStackTraceInitialDelayMillis(100);
        coreConfiguration.setStackTracePeriodMillis(10);
        container.getInformant().updateCoreConfiguration(coreConfiguration);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.get(0).getMergedStackTree(), is(notNullValue()));
    }

    public static class ShouldGenerateTraceWithMergedStackTree implements AppUnderTest,
            RootSpanMarker {
        public void executeApp() throws InterruptedException {
            rootSpanMarker();
        }
        public void rootSpanMarker() throws InterruptedException {
            Thread.sleep(150);
        }
    }
}
