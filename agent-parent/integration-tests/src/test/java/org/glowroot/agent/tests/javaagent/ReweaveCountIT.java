/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;

import static org.assertj.core.api.Assertions.assertThat;

public class ReweaveCountIT {

    protected static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.createJavaagent();
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
    public void shouldCalculateCorrectReweaveCount() throws Exception {
        container.executeNoExpectedTrace(ShouldLoadClassesForWeaving.class);
        InstrumentationConfig config = InstrumentationConfig.newBuilder()
                .setClassName("org.glowroot.agent.tests.javaagent.ReweaveCountIT$AAA")
                .setMethodName("x")
                .setMethodReturnType("")
                .setCaptureKind(CaptureKind.TIMER)
                .setTimerName("x")
                .build();
        int reweaveCount =
                container.getConfigService().updateInstrumentationConfigs(ImmutableList.of(config));
        assertThat(reweaveCount).isEqualTo(2);
        reweaveCount = container.getConfigService()
                .updateInstrumentationConfigs(ImmutableList.<InstrumentationConfig>of());
        assertThat(reweaveCount).isEqualTo(2);
    }

    public static class ShouldLoadClassesForWeaving implements AppUnderTest {
        @Override
        public void executeApp() {
            new BBB();
            new CCC();
        }
    }

    private static class AAA {
        @SuppressWarnings("unused")
        protected void x() {}
    }

    private static class BBB extends AAA {
        @Override
        protected void x() {}
    }

    private static class CCC extends AAA {}
}
