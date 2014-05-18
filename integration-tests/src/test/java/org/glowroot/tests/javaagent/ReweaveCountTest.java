/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.tests.javaagent;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.config.PointcutConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ReweaveCountTest {

    protected static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedJavaagentContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
        container.getConfigService().reweaveAdhocPointcuts();
    }

    @Test
    public void shouldCalculateCorrectReweaveCount() throws Exception {
        container.executeAppUnderTest(ShouldLoadClassesForWeaving.class);
        PointcutConfig config = new PointcutConfig();
        config.setType("org.glowroot.tests.javaagent.ReweaveCountTest$AAA");
        config.setMethodName("x");
        config.setMethodArgTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setTraceMetric("x");
        String configVersion = container.getConfigService().addAdhocPointcutConfig(config);
        int reweaveCount = container.getConfigService().reweaveAdhocPointcuts();
        assertThat(reweaveCount).isEqualTo(2);
        container.getConfigService().removeAdhocPointcutConfig(configVersion);
        reweaveCount = container.getConfigService().reweaveAdhocPointcuts();
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
