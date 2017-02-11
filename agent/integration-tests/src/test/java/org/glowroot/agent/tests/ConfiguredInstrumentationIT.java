/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.tests;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.LocalContainer;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.MethodModifier;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfiguredInstrumentationIT {

    protected static Container container;
    private static File testDir;

    @BeforeClass
    public static void setUp() throws Exception {
        testDir = TempDirs.createTempDir("glowroot-test-dir");
        // see subclass (ReweavePointcutsTest) for JavaagentContainer test
        container = LocalContainer.create(testDir);
        List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();
        instrumentationConfigs.add(buildInstrumentationForExecute1());
        instrumentationConfigs.add(buildInstrumentationForExecute1TimerOnly());
        instrumentationConfigs.add(buildInstrumentationForExecuteWithReturn());
        instrumentationConfigs.add(buildInstrumentationForExecuteWithArgs());
        container.getConfigService().updateInstrumentationConfigs(instrumentationConfigs);
        // TODO this sleep currently resolves sporadic grpc related failure, try without sleep after
        // next grpc update
        Thread.sleep(1000);
        // re-start now with pointcut configs
        container.close();
        // see subclass (ReweavePointcutsTest) for JavaagentContainer test
        container = LocalContainer.create(testDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(testDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExecute1() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(ProtoOptional.of(Integer.MAX_VALUE))
                        .build());

        // when
        Trace trace = container.execute(ShouldExecute1.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getTransactionType()).isEqualTo("test override type");
        assertThat(header.getTransactionName()).isEqualTo("test override name");
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");
        assertThat(rootTimer.getChildTimerList()).hasSize(1);
        assertThat(rootTimer.getChildTimerList().get(0).getName()).isEqualTo("execute one");
        assertThat(rootTimer.getChildTimerList().get(0).getChildTimerList()).hasSize(1);
        assertThat(rootTimer.getChildTimerList().get(0).getChildTimerList().get(0).getName())
                .isEqualTo("execute one timer only");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("execute1() => void");

        assertThat(entry.getLocationStackTraceElementList()).isNotEmpty();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldRenderTraceEntryMessageTemplateWithReturnValue() throws Exception {
        // when
        Trace trace = container.execute(ShouldExecuteWithReturn.class);

        // then
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");
        assertThat(rootTimer.getChildTimerList()).hasSize(1);
        assertThat(rootTimer.getChildTimerList().get(0).getName()).isEqualTo("execute with return");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("executeWithReturn() => xyz");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldRenderTraceHeadline() throws Exception {
        // when
        Trace trace = container.execute(ShouldExecuteWithArgs.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("executeWithArgs(): abc, 123, the name");
        assertThat(header.getTransactionType()).isEqualTo("Pointcut config test");
        assertThat(header.getTransactionName()).isEqualTo("Misc / executeWithArgs");
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getName()).isEqualTo("execute with args");
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(header.getEntryCount()).isZero();
    }

    protected static InstrumentationConfig buildInstrumentationForExecute1() throws Exception {
        return InstrumentationConfig.newBuilder()
                .setClassName("org.glowroot.agent.tests.ConfiguredInstrumentationIT$Misc")
                .setMethodName("execute1")
                .setMethodReturnType("")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TRACE_ENTRY)
                .setTimerName("execute one")
                .setTraceEntryMessageTemplate("execute1() => {{_}}")
                .setTraceEntryStackThresholdMillis(ProtoOptional.of(0))
                .setTransactionType("test override type")
                .setTransactionNameTemplate("test override name")
                .setTransactionSlowThresholdMillis(ProtoOptional.of(0))
                .build();
    }

    protected static InstrumentationConfig buildInstrumentationForExecute1TimerOnly()
            throws Exception {
        return InstrumentationConfig.newBuilder()
                .setClassName("org.glowroot.agent.tests.ConfiguredInstrumentationIT$Misc")
                .setMethodName("execute1")
                .setMethodReturnType("")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TIMER)
                .setTimerName("execute one timer only")
                .build();
    }

    protected static InstrumentationConfig buildInstrumentationForExecuteWithReturn()
            throws Exception {
        return InstrumentationConfig.newBuilder()
                .setClassName("org.glowroot.agent.tests.ConfiguredInstrumentationIT$Misc")
                .setMethodName("executeWithReturn")
                .setMethodReturnType("")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TRACE_ENTRY)
                .setTimerName("execute with return")
                .setTraceEntryMessageTemplate("executeWithReturn() => {{_}}")
                .build();
    }

    protected static InstrumentationConfig buildInstrumentationForExecuteWithArgs()
            throws Exception {
        return InstrumentationConfig.newBuilder()
                .setClassName("org.glowroot.agent.tests.ConfiguredInstrumentationIT$Misc")
                .setMethodName("executeWithArgs")
                .addMethodParameterType("java.lang.String")
                .addMethodParameterType("int")
                .addMethodParameterType(
                        "org.glowroot.agent.tests.ConfiguredInstrumentationIT$BasicMisc")
                .setMethodReturnType("void")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TRANSACTION)
                .setTimerName("execute with args")
                .setTraceEntryMessageTemplate("executeWithArgs(): {{0}}, {{1}}, {{2.name}}")
                .setTransactionType("Pointcut config test")
                .setTransactionNameTemplate("Misc / {{methodName}}")
                .build();
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
