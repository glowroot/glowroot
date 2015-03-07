/*
 * Copyright 2013-2015 the original author or authors.
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

import java.io.File;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.InstrumentationConfig;
import org.glowroot.container.config.InstrumentationConfig.CaptureKind;
import org.glowroot.container.config.InstrumentationConfig.MethodModifier;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfiguredInstrumentationTest {

    protected static Container container;
    private static File dataDir;

    @BeforeClass
    public static void setUp() throws Exception {
        dataDir = TempDirs.createTempDir("glowroot-test-datadir");
        container = Containers.createWithFileDb(dataDir);
        addInstrumentationForExecute1();
        addInstrumentationForExecute1TimerOnly();
        addInstrumentationForExecuteWithReturn();
        addInstrumentationForExecuteWithArgs();
        // re-start now with pointcut configs
        container.close();
        container = Containers.createWithFileDb(dataDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(dataDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExecute1() throws Exception {
        // given
        GeneralConfig config = container.getConfigService().getGeneralConfig();
        config.setTraceStoreThresholdMillis(Integer.MAX_VALUE);
        container.getConfigService().updateGeneralConfig(config);
        // when
        container.executeAppUnderTest(ShouldExecute1.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        assertThat(trace.getTransactionType()).isEqualTo("test override type");
        assertThat(trace.getTransactionName()).isEqualTo("test override name");
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("execute one");
        assertThat(trace.getRootTimer().getNestedTimers().get(0)
                .getNestedTimerNames()).containsOnly("execute one timer only");
        assertThat(entries.get(1).getMessage().getText()).isEqualTo("execute1() => void");
        assertThat(entries.get(1).getStackTrace()).isNotNull();
    }

    @Test
    public void shouldRenderTraceEntryTemplateWithReturnValue() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithReturn.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("execute with return");
        assertThat(entries.get(1).getMessage().getText()).isEqualTo("executeWithReturn() => xyz");
    }

    @Test
    public void shouldRenderTraceHeadline() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithArgs.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("executeWithArgs(): abc, 123, the name");
        assertThat(trace.getTransactionType()).isEqualTo("Pointcut config test");
        assertThat(trace.getTransactionName()).isEqualTo("Misc / executeWithArgs");
        assertThat(entries).hasSize(1);
        assertThat(trace.getRootTimer().getName()).isEqualTo("execute with args");
        assertThat(trace.getRootTimer().getNestedTimers()).isEmpty();
        assertThat(entries.get(0).getMessage().getText())
                .isEqualTo("executeWithArgs(): abc, 123, the name");
    }

    protected static void addInstrumentationForExecute1() throws Exception {
        InstrumentationConfig config = new InstrumentationConfig();
        config.setClassName("org.glowroot.tests.ConfiguredInstrumentationTest$Misc");
        config.setMethodName("execute1");
        config.setMethodParameterTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setCaptureKind(CaptureKind.TRACE_ENTRY);
        config.setTimerName("execute one");
        config.setTraceEntryTemplate("execute1() => {{_}}");
        config.setTraceEntryStackThresholdMillis(0L);
        config.setTransactionType("test override type");
        config.setTransactionNameTemplate("test override name");
        config.setTraceStoreThresholdMillis(0L);
        container.getConfigService().addInstrumentationConfig(config);
    }

    protected static void addInstrumentationForExecute1TimerOnly() throws Exception {
        InstrumentationConfig config = new InstrumentationConfig();
        config.setClassName("org.glowroot.tests.ConfiguredInstrumentationTest$Misc");
        config.setMethodName("execute1");
        config.setMethodParameterTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setCaptureKind(CaptureKind.TIMER);
        config.setTimerName("execute one timer only");
        container.getConfigService().addInstrumentationConfig(config);
    }

    protected static void addInstrumentationForExecuteWithReturn() throws Exception {
        InstrumentationConfig config = new InstrumentationConfig();
        config.setClassName("org.glowroot.tests.ConfiguredInstrumentationTest$Misc");
        config.setMethodName("executeWithReturn");
        config.setMethodParameterTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setCaptureKind(CaptureKind.TRACE_ENTRY);
        config.setTimerName("execute with return");
        config.setTraceEntryTemplate("executeWithReturn() => {{_}}");
        container.getConfigService().addInstrumentationConfig(config);
    }

    protected static void addInstrumentationForExecuteWithArgs() throws Exception {
        InstrumentationConfig config = new InstrumentationConfig();
        config.setClassName("org.glowroot.tests.ConfiguredInstrumentationTest$Misc");
        config.setMethodName("executeWithArgs");
        config.setMethodParameterTypes(ImmutableList.of("java.lang.String", "int",
                "org.glowroot.tests.ConfiguredInstrumentationTest$BasicMisc"));
        config.setMethodReturnType("void");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setCaptureKind(CaptureKind.TRANSACTION);
        config.setTimerName("execute with args");
        config.setTraceEntryTemplate("executeWithArgs(): {{0}}, {{1}}, {{2.name}}");
        config.setTransactionType("Pointcut config test");
        config.setTransactionNameTemplate("Misc / {{methodName}}");
        container.getConfigService().addInstrumentationConfig(config);
    }

    public interface Misc {
        public void execute1();
        public CharSequence executeWithReturn();
        public void executeWithArgs(String one, int two, BasicMisc anotherMisc);
    }

    public static class BasicMisc implements Misc {
        String getName() {
            return "the name";
        }
        @Override
        public void execute1() {}
        @Override
        public String executeWithReturn() {
            return "xyz";
        }
        @Override
        public void executeWithArgs(String one, int two, BasicMisc anotherMisc) {}
    }

    public static class ShouldExecute1 implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            new BasicMisc().execute1();
        }
    }

    public static class ShouldExecuteWithReturn implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            new BasicMisc().executeWithReturn();
        }
    }

    public static class ShouldExecuteWithArgs implements AppUnderTest {
        @Override
        public void executeApp() {
            new BasicMisc().executeWithArgs("abc", 123, new BasicMisc());
        }
    }
}
