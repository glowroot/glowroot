/*
 * Copyright 2018-2019 the original author or authors.
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

import java.io.File;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

public class FileInstrumentationPresentAtStartupIT {

    protected static Container container;
    private static File testDir;

    @BeforeAll
    public static void setUp() throws Exception {
        testDir = TempDirs.createTempDir("glowroot-test-dir");
        container = Containers.create(testDir);
        container.getConfigService().updateInstrumentationConfigs(ImmutableList.of(
                InstrumentationConfig.newBuilder()
                        .setClassName("java.io.File")
                        .setMethodName("getPath")
                        .setMethodReturnType("")
                        .setCaptureKind(CaptureKind.TRANSACTION)
                        .setTimerName("get path")
                        .setTraceEntryMessageTemplate("getPath() => {{_}}")
                        .setTransactionType("FILE")
                        .setTransactionNameTemplate("getPath")
                        .setTransactionSlowThresholdMillis(OptionalInt32.newBuilder()
                                .setValue(1000))
                        .build()));
        // re-start now with instrumentation configs
        container.close();
        container = Containers.create(testDir);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(testDir);
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExecute1() throws Exception {
        // just verify that set up / tear down doesn't fail
    }
}
