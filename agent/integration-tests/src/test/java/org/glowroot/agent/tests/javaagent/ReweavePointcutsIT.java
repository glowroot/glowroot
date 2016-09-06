/*
 * Copyright 2013-2016 the original author or authors.
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

import java.util.List;

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.tests.ConfiguredInstrumentationIT;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;

public class ReweavePointcutsIT extends ConfiguredInstrumentationIT {

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.createJavaagent();
        // make sure the classes are loaded once before re-weaving
        container.execute(ShouldExecute1.class);
        container.execute(ShouldExecuteWithReturn.class);
        container.executeNoExpectedTrace(ShouldExecuteWithArgs.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Before
    public void beforeEachTest() throws Exception {
        List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();
        instrumentationConfigs.add(buildInstrumentationForExecute1());
        instrumentationConfigs.add(buildInstrumentationForExecute1TimerOnly());
        instrumentationConfigs.add(buildInstrumentationForExecuteWithReturn());
        instrumentationConfigs.add(buildInstrumentationForExecuteWithArgs());
        container.getConfigService().updateInstrumentationConfigs(instrumentationConfigs);
    }

    @Override
    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Override
    @Test
    public void shouldExecute1() throws Exception {
        super.shouldExecute1();
    }

    @Override
    @Test
    public void shouldRenderTraceEntryMessageTemplateWithReturnValue() throws Exception {
        super.shouldRenderTraceEntryMessageTemplateWithReturnValue();
    }

    @Override
    @Test
    public void shouldRenderTraceHeadline() throws Exception {
        super.shouldRenderTraceHeadline();
    }
}
