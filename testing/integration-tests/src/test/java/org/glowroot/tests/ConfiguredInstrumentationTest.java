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
import org.glowroot.container.TransactionMarker;
import org.glowroot.container.config.InstrumentationConfig;
import org.glowroot.container.config.InstrumentationConfig.CaptureKind;
import org.glowroot.container.config.InstrumentationConfig.MethodModifier;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfiguredInstrumentationTest {

    protected static Container container;
    private static File baseDir;

    @BeforeClass
    public static void setUp() throws Exception {
        baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        container = Containers.createWithFileDb(baseDir);
        addInstrumentationForExecute1();
        addInstrumentationForExecute1TimerOnly();
        addInstrumentationForExecuteWithReturn();
        addInstrumentationForExecuteWithArgs();
        // re-start now with pointcut configs
        container.close();
        container = Containers.createWithFileDb(baseDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(baseDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExecute1() throws Exception {
        // given
        TransactionConfig config = container.getConfigService().getTransactionConfig();
        config.setSlowThresholdMillis(Integer.MAX_VALUE);
        container.getConfigService().updateTransactionConfig(config);
        // when
        container.executeAppUnderTest(ShouldExecute1.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(header.transactionType()).isEqualTo("test override type");
        assertThat(header.transactionName()).isEqualTo("test override name");
        assertThat(header.rootTimer().name()).isEqualTo("mock trace marker");
        assertThat(header.rootTimer().childTimers()).hasSize(1);
        assertThat(header.rootTimer().childTimers().get(0).name())
                .isEqualTo("execute one");
        assertThat(header.rootTimer().childTimers().get(0).childTimers()).hasSize(1);
        assertThat(header.rootTimer().childTimers().get(0).childTimers().get(0)
                .name())
                        .isEqualTo("execute one timer only");
        Trace.Entry entry = entries.get(0);
        assertThat(entry.message()).isEqualTo("execute1() => void");
        assertThat(entry.locationStackTraceElements()).isNotEmpty();
    }

    @Test
    public void shouldRenderTraceEntryTemplateWithReturnValue() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithReturn.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().name()).isEqualTo("mock trace marker");
        assertThat(header.rootTimer().childTimers()).hasSize(1);
        assertThat(header.rootTimer().childTimers().get(0).name())
                .isEqualTo("execute with return");
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo("executeWithReturn() => xyz");
    }

    @Test
    public void shouldRenderTraceHeadline() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithArgs.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("executeWithArgs(): abc, 123, the name");
        assertThat(header.transactionType()).isEqualTo("Pointcut config test");
        assertThat(header.transactionName()).isEqualTo("Misc / executeWithArgs");
        assertThat(header.rootTimer().name()).isEqualTo("execute with args");
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.entryCount()).isZero();
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
        config.setTransactionSlowThresholdMillis(0L);
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

    public static class ShouldExecute1 implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            new BasicMisc().execute1();
        }
    }

    public static class ShouldExecuteWithReturn implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
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
