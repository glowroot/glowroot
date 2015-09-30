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

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.config.AdvancedConfig;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class GcActivityTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
    public void shouldReadTraceThreadInfoConfigEnabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GenerateTraceWithGc.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.gcActivities()).isNotEmpty();
    }

    @Test
    public void shouldReadTraceThreadInfoConfigDisabled() throws Exception {
        // given
        AdvancedConfig advancedConfig = container.getConfigService().getAdvancedConfig();
        advancedConfig.setCaptureGcActivity(false);
        container.getConfigService().updateAdvancedConfig(advancedConfig);
        // when
        container.executeAppUnderTest(GenerateTraceWithGc.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.gcActivities()).isEmpty();
    }

    public static class GenerateTraceWithGc implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            System.gc();
        }
    }
}
